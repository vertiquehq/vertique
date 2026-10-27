// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.executor;

import dev.vertique.workflow.engine.spi.CommandHead;
import dev.vertique.workflow.engine.spi.WorkflowCommandRepository;
import dev.vertique.workflow.executor.WorkflowExecutorEvent.CommandDeferred;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.types.EchoedNames;
import io.vertx.core.Future;
import io.vertx.sqlclient.SqlClient;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * INTERNAL: decides that this node cannot run a peeked command and defers the command instead of claiming it.
 *
 * <p>A command is not runnable here when the node's registry holds no plan for the definition key the instance is
 * pinned to, or holds a plan whose hash differs from the plan hash the instance is pinned to, or, for a migration,
 * holds no plan for the version the instance moves to. A deferral moves the
 * command's availability forward by a fixed backoff that doubles from five seconds to a cap of five minutes. It spends
 * no attempt, so another node that can run the plan still claims the command, and a command nobody can run is reported
 * as stuck by its age.
 *
 * <p>The backoff is computed from the number of deferrals the command had before this one.
 */
public final class Deferral {

    private static final Logger log = LoggerFactory.getLogger(Deferral.class);

    private static final long BASE_MS = 5_000L;

    private static final long CAP_MS = 300_000L;

    /** The count from which the doubled base is at least the cap. */
    private static final int CAPPED_FROM = 6;

    private final WorkflowCommandRepository<SqlClient> commands;
    private final ExecutorEvents events;

    Deferral(WorkflowCommandRepository<SqlClient> commands, ExecutorEvents events) {
        this.commands = Objects.requireNonNull(commands, "commands");
        this.events = Objects.requireNonNull(events, "events");
    }

    /**
     * Whether this node can run the command: its registry holds the pinned definition key and, when the head names a
     * plan hash, the plan held has that hash.
     *
     * @param registry the registry of plans this node holds
     * @param head     the peeked command
     * @return {@code true} when the node can run the command
     */
    static boolean runnable(WorkflowRegistry registry, CommandHead head) {
        Optional<RuntimeWorkflow> held;
        try {
            held = registry.find(
                    new DefinitionKey(head.definitionScopeKey(), head.definitionId(), head.definitionVersion()));
        } catch (RuntimeException e) {
            return false;
        }
        if (held.isEmpty()) {
            return false;
        }
        if (head.planHash() != null && !head.planHash().equals(held.get().plan().planHash())) {
            return false;
        }
        return head.migrationVersion() == null || targetRunnable(registry, head);
    }

    /**
     * Whether the registry holds the plan a migration moves its instance to. A target no definition key can name is
     * left to the segment, which rejects the migration.
     */
    private static boolean targetRunnable(WorkflowRegistry registry, CommandHead head) {
        DefinitionKey target;
        try {
            target = new DefinitionKey(
                    head.migrationScope() != null ? head.migrationScope() : head.definitionScopeKey(),
                    head.definitionId(),
                    head.migrationVersion());
        } catch (IllegalArgumentException e) {
            return true;
        }
        try {
            return registry.find(target).isPresent();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * The pause before a deferred command is available again.
     *
     * @param deferralCountBeforeIncrement how many times the command was deferred before this deferral
     * @return five seconds doubled once per earlier deferral, at most five minutes, in milliseconds
     */
    public static long backoffMs(int deferralCountBeforeIncrement) {
        if (deferralCountBeforeIncrement >= CAPPED_FROM) {
            return CAP_MS;
        }
        return Math.min(BASE_MS << Math.max(0, deferralCountBeforeIncrement), CAP_MS);
    }

    /**
     * Defers a command this node cannot run. The future never fails: a failed deferral is logged at warn level with
     * the definition key only, and the command is peeked again on the next pass.
     *
     * @param head the peeked command
     * @return a future of whether the command was deferred; {@code false} when the repository wrote nothing
     */
    Future<Boolean> defer(CommandHead head) {
        Future<Integer> deferred;
        try {
            deferred = Objects.requireNonNull(commands.defer(head), "defer returned null instead of a future");
        } catch (Exception | LinkageError | AssertionError e) {
            deferred = Future.failedFuture(e);
        }
        return deferred.map(count -> {
                    if (count == null || count <= 0) {
                        return false;
                    }
                    events.emit(new CommandDeferred(head.kind(), count));
                    return true;
                })
                .recover(failure -> {
                    log.warn(
                            "A command could not be deferred: no runnable plan for {} version {} in scope {}: {}",
                            EchoedNames.bounded(head.definitionId()),
                            head.definitionVersion(),
                            EchoedNames.bounded(head.definitionScopeKey()),
                            failure.getClass().getName());
                    log.debug("Deferral failure", failure);
                    return Future.succeededFuture(false);
                });
    }
}
