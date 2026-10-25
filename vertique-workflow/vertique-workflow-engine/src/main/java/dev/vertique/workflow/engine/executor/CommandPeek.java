// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.executor;

import dev.vertique.workflow.engine.spi.CommandHead;
import dev.vertique.workflow.engine.spi.WorkflowCommandRepository;
import io.vertx.core.Future;
import io.vertx.sqlclient.SqlClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * INTERNAL: reads the commands an executor could claim, defers those this node cannot run and makes the plans of the
 * rest resident, outside any transaction.
 *
 * <p>A candidate whose instance has ended is kept first, without any plan lookup: its claim settles it without a plan,
 * so a removed plan cannot leave it unsettled and it is never deferred. Only a candidate on a live instance is judged
 * by {@link Deferral#runnable}; one this node cannot run is deferred instead of claimed.
 *
 * <p>A peeked command whose plan the node holds but cannot make resident is skipped for this peek and logged at debug
 * level with its ids only, never a payload; nothing is claimed, no attempt is spent and nothing is written, so the
 * command is peeked again on the next pass.
 */
final class CommandPeek {

    private static final Logger log = LoggerFactory.getLogger(CommandPeek.class);

    /**
     * What a peek came to.
     *
     * @param heads    the commands to claim
     * @param deferred how many commands the peek deferred
     */
    record Peeked(List<CommandHead> heads, int deferred) {}

    private final WorkflowCommandRepository<SqlClient> commands;
    private final WorkflowSegments segments;
    private final Deferral deferral;

    CommandPeek(WorkflowCommandRepository<SqlClient> commands, WorkflowSegments segments, Deferral deferral) {
        this.commands = Objects.requireNonNull(commands, "commands");
        this.segments = Objects.requireNonNull(segments, "segments");
        this.deferral = Objects.requireNonNull(deferral, "deferral");
    }

    /**
     * Peeks at most {@code limit} claimable commands, oldest availability first, defers those this node cannot run
     * and keeps those it can.
     *
     * @param limit the most commands to peek
     * @return a future of the commands to claim and the number deferred
     * @throws NullPointerException if the repository returns no future
     */
    Future<Peeked> resident(int limit) {
        Future<List<CommandHead>> peeked =
                Objects.requireNonNull(commands.peek(limit), "peek returned null instead of a future");
        return peeked.compose(heads -> {
            List<CommandHead> resident = new ArrayList<>();
            List<Future<Boolean>> deferrals = new ArrayList<>();
            if (heads != null) {
                for (CommandHead head : heads) {
                    if (head.instanceEnded()) {
                        resident.add(head);
                    } else if (!Deferral.runnable(segments.registry(), head)) {
                        deferrals.add(deferral.defer(head));
                    } else if (segments.makeResident(head)) {
                        resident.add(head);
                    } else {
                        log.debug(
                                "Skipping command {} of instance {}: no plan for {} version {} in scope {}",
                                head.commandId(),
                                head.workflowId().value(),
                                head.definitionId(),
                                head.definitionVersion(),
                                head.definitionScopeKey());
                    }
                }
            }
            return Future.all(deferrals).map(ignored -> {
                int deferred = 0;
                for (Future<Boolean> result : deferrals) {
                    if (result.result()) {
                        deferred++;
                    }
                }
                return new Peeked(resident, deferred);
            });
        });
    }
}
