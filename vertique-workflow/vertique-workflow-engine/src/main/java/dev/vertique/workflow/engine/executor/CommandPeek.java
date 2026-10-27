// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.executor;

import dev.vertique.workflow.engine.executor.WorkflowSegments.Warm;
import dev.vertique.workflow.engine.executor.WorkflowSegments.Warm.Marks;
import dev.vertique.workflow.engine.spi.CommandHead;
import dev.vertique.workflow.engine.spi.WorkflowCommandRepository;
import dev.vertique.workflow.types.EchoedNames;
import io.vertx.core.Future;
import io.vertx.sqlclient.SqlClient;
import jakarta.annotation.Nullable;
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
 * so a removed plan cannot leave it unsettled and it is never deferred. Only a candidate on a live instance is warmed,
 * through {@link WorkflowSegments#warm}, which loads its plan into the node's plan cache when the node does not hold
 * it and holds the plan for the segment; one this node cannot run is deferred instead of claimed, and the failing marks
 * the warm-up found are written after the deferral.
 *
 * <p>A peeked command whose plan could not be read for a reason that says nothing about the plan, such as a failed
 * row read, is skipped for this peek and logged at debug level with its ids only, never a payload; nothing is claimed,
 * no attempt is spent and nothing is written, so the command is peeked again on the next pass.
 */
final class CommandPeek {

    private static final Logger log = LoggerFactory.getLogger(CommandPeek.class);

    /**
     * A command to claim and the hold on the plans warmed for it.
     *
     * @param head    the peeked command
     * @param release gives up the hold; idempotent. The executor runs it when the command is not claimed, and when its
     *                segment ends
     * @param marks   what the warm-up found out about failing versions; its write runs once the segment has ended
     */
    record WarmedHead(CommandHead head, Runnable release, Marks marks) {}

    /**
     * What a peek came to.
     *
     * @param heads    the commands to claim
     * @param deferred how many commands the peek deferred
     */
    record Peeked(List<WarmedHead> heads, int deferred) {}

    /** What became of one peeked command: to claim, deferred, or left for the next peek. */
    private record Slot(@Nullable WarmedHead head, boolean deferred) {}

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
            List<Future<Slot>> slots = new ArrayList<>();
            if (heads != null) {
                for (CommandHead head : heads) {
                    slots.add(head.instanceEnded() ? Future.succeededFuture(ended(head)) : warm(head));
                }
            }
            // the slots keep the order of the peek: the oldest availability is claimed first
            return Future.all(slots).map(ignored -> {
                List<WarmedHead> resident = new ArrayList<>();
                int deferred = 0;
                for (Future<Slot> slot : slots) {
                    if (slot.result().head() != null) {
                        resident.add(slot.result().head());
                    }
                    if (slot.result().deferred()) {
                        deferred++;
                    }
                }
                return new Peeked(resident, deferred);
            });
        });
    }

    private static Slot ended(CommandHead head) {
        return new Slot(new WarmedHead(head, () -> {}, Marks.NONE), false);
    }

    /** Warms the plans of a command on a live instance and defers the command when this node cannot run it. */
    private Future<Slot> warm(CommandHead head) {
        Future<Warm> warmed;
        try {
            warmed = Objects.requireNonNull(segments.warm(head), "warm returned null instead of a future");
        } catch (RuntimeException e) {
            warmed = Future.failedFuture(e);
        }
        return warmed.transform(result -> {
            if (result.failed()) {
                skipped(head);
                return Future.succeededFuture(new Slot(null, false));
            }
            Warm warm = result.result();
            switch (warm.status()) {
                case RESIDENT -> {
                    return Future.succeededFuture(new Slot(new WarmedHead(head, warm.release(), warm.marks()), false));
                }
                case NOT_RUNNABLE -> {
                    // the marks are written after the deferral has committed, and never change its outcome
                    return deferral.defer(head)
                            .compose(deferred -> warm.marks().run().map(new Slot(null, deferred)));
                }
                default -> {
                    skipped(head);
                    return Future.succeededFuture(new Slot(null, false));
                }
            }
        });
    }

    private static void skipped(CommandHead head) {
        log.debug(
                "Skipping command {} of instance {}: no plan for {} version {} in scope {}",
                head.commandId(),
                head.workflowId().value(),
                EchoedNames.bounded(head.definitionId()),
                head.definitionVersion(),
                EchoedNames.bounded(head.definitionScopeKey()));
    }
}
