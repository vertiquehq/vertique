// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.executor;

import dev.vertique.workflow.engine.spi.ClaimedCommand;
import dev.vertique.workflow.engine.spi.CommandHead;
import dev.vertique.workflow.engine.spi.SegmentCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.registry.store.DefinitionFailure;
import io.vertx.core.Future;
import io.vertx.sqlclient.SqlClient;
import jakarta.annotation.Nullable;
import java.util.function.Supplier;

/**
 * INTERNAL: the engine's side of a segment, which the executor reaches through this seam so that the engine's
 * package-private collaborators stay package-private.
 *
 * <p>The executor claims a command, fences it and settles it. This seam is what runs in between: it makes the plan of
 * a peeked command resident before the claim, and runs the command's work inside the segment's transaction.
 */
public interface WorkflowSegments {

    /**
     * What a command's work came to.
     */
    enum Outcome {
        /** The command was applied; the executor completes it. */
        APPLIED,
        /** The command no longer applies to its instance; the executor rejects it. */
        STALE,
        /** This node cannot run the pinned plan now (another plan is pinned, or it no longer resolves); the claim is released. */
        PIN_MISMATCH,
        /**
         * The command is not awaited by its instance. The engine has parked it in the segment's transaction, restoring
         * the attempt its claim spent; the executor writes no settlement.
         */
        PARKED,
        /**
         * The engine rejected the command in the segment's transaction, with its reason and history; the executor writes
         * no settlement.
         */
        SETTLED_REJECTED,
        /**
         * The engine settled the command as stale in the segment's transaction, with its reason and history; the
         * executor writes no settlement.
         */
        SETTLED_STALE,
        /**
         * The engine settled the command as cancelled in the segment's transaction, because its instance was cancelled,
         * with its reason and history; the executor writes no settlement.
         */
        SETTLED_CANCELLED
    }

    /**
     * What warming the plans of a peeked command came to.
     *
     * @param status  whether the command can run here
     * @param release gives up the hold on the plans the warm-up took; idempotent, and a no-op unless the status is
     *                {@link Status#RESIDENT}
     * @param marks   what the warm-up found out about failing versions
     */
    record Warm(Status status, Runnable release, Marks marks) {

        /**
         * What a warm-up found out about whether the versions it loaded are failing.
         *
         * @param failing the failure the version the command is pinned to is evaluated with, or {@code null} when it
         *                is not failing
         * @param write   writes the evaluations to the rows when they differ from the stored marks; called once the
         *                command's segment or deferral has ended, and its future never fails
         */
        public record Marks(@Nullable DefinitionFailure failing, Supplier<Future<Void>> write) {

            /** No version is failing and nothing is to be written. */
            public static final Marks NONE = new Marks(null, Future::succeededFuture);

            /**
             * Writes the marks; a failure is the writer's to log, so the returned future never fails.
             *
             * @return a future that completes when the writes have ended
             */
            public Future<Void> run() {
                try {
                    return write.get().recover(failure -> Future.succeededFuture());
                } catch (RuntimeException e) {
                    return Future.succeededFuture();
                }
            }
        }

        /** The status of a warm-up. */
        public enum Status {
            /** The plans are resident and held until released; the command can be claimed. */
            RESIDENT,
            /**
             * The node cannot run the command now: no row supplies the plan, it failed to build, or its hash differs
             * from the instance's pin. The executor defers the command.
             */
            NOT_RUNNABLE,
            /** A plan could not be read for a reason that says nothing about it; nothing is written and the command is peeked again. */
            UNAVAILABLE
        }

        private static final Runnable NOTHING = () -> {};

        /**
         * A warm-up that holds no plan.
         *
         * @param status the status
         * @return the result
         */
        public static Warm of(Status status) {
            return new Warm(status, NOTHING, Marks.NONE);
        }

        /**
         * A warm-up that holds no plan and has marks to write.
         *
         * @param status the status
         * @param marks  the marks
         * @return the result
         */
        public static Warm of(Status status, Marks marks) {
            return new Warm(status, NOTHING, marks);
        }
    }

    /**
     * Warms the plans of a peeked command through {@link WorkflowRegistry#load}, outside any transaction, and holds
     * them until the returned {@link Warm#release()} runs, so that a cache eviction cannot make a lookup inside the
     * segment miss. The executor releases the hold when the segment ends, or when the command is not claimed.
     *
     * <p>The default warms through the registry and holds nothing, which is right for a registry that evicts nothing.
     *
     * @param head the peeked command
     * @return a future of the warm-up; it never fails
     */
    default Future<Warm> warm(CommandHead head) {
        return PlanWarming.warm(registry(), null, head);
    }

    /**
     * The registry of plans this node holds, from which the executor decides whether it can run a peeked command or
     * must defer it.
     *
     * @return the node's registry
     */
    WorkflowRegistry registry();

    /**
     * Runs the work of a fenced command inside the segment's transaction: computes the initial state of a start and
     * advances the instance until it waits or ends. Writes nothing when it reports {@link Outcome#PIN_MISMATCH}.
     *
     * @param head    the peeked command the claim came from
     * @param claim   the claim
     * @param command the fenced command
     * @param tx      the segment's transaction handle
     * @return a future of the outcome; it fails when the work fails, and the transaction then rolls back
     */
    Future<Outcome> run(CommandHead head, ClaimedCommand claim, SegmentCommand command, SqlClient tx);

    /**
     * Runs the work of a fenced command like {@link #run(CommandHead, ClaimedCommand, SegmentCommand, SqlClient)}, with
     * a cap on the nodes the segment may execute. A segment that executes more nodes than the cap without reaching a
     * wait fails with a {@link StepLimitExceededException} and the transaction rolls back.
     *
     * <p>The default ignores the cap.
     *
     * @param head     the peeked command the claim came from
     * @param claim    the claim
     * @param command  the fenced command
     * @param tx       the segment's transaction handle
     * @param maxSteps the most nodes the segment may execute; at least 1
     * @return a future of the outcome; it fails when the work fails, and the transaction then rolls back
     */
    default Future<Outcome> run(
            CommandHead head, ClaimedCommand claim, SegmentCommand command, SqlClient tx, int maxSteps) {
        return run(head, claim, command, tx);
    }

    /**
     * Adds what a failed instance carries beyond its rows, such as its lifecycle event, in the transaction in which
     * the executor failed the instance on behalf of a command. Called after the instance row and its history entry are
     * written; a failed future rolls the transaction back.
     *
     * <p>The default does nothing.
     *
     * @param instance        the failed instance
     * @param historySequence the sequence of the history entry that records the failure
     * @param reasonCode      the reason code stored on the instance
     * @param message         the sanitized reason stored on the instance
     * @param tx              the transaction that fails the instance
     * @return a future that completes when the writes are done
     */
    default Future<Void> instanceFailed(
            WorkflowInstanceId instance, long historySequence, String reasonCode, String message, SqlClient tx) {
        return Future.succeededFuture();
    }

    /**
     * Runs the work the executor does for a claimed command once it holds the instance's execution lock, so that the
     * history entries it writes are announced when the transaction commits. The work covers the fence, the command's
     * segment and the settlement.
     *
     * <p>The default only runs the work.
     *
     * @param instance the instance the command belongs to, whose execution lock the transaction holds
     * @param tx       the segment's transaction handle
     * @param work     the work
     * @param <T>      the result of the work
     * @return the future of the work, unchanged
     */
    default <T> Future<T> announcing(WorkflowInstanceId instance, SqlClient tx, Supplier<Future<T>> work) {
        return work.get();
    }
}
