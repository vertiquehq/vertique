// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.executor;

import dev.vertique.workflow.engine.spi.ClaimedCommand;
import dev.vertique.workflow.engine.spi.CommandHead;
import dev.vertique.workflow.engine.spi.SegmentCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import io.vertx.core.Future;
import io.vertx.sqlclient.SqlClient;

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
        /** The instance is pinned to another plan than the one made resident; the executor releases the claim. */
        PIN_MISMATCH
    }

    /**
     * Makes the plan of a peeked command resident. Runs outside any transaction.
     *
     * @param head the peeked command
     * @return {@code true} when the plan is resident, {@code false} when this node cannot resolve it
     */
    boolean makeResident(CommandHead head);

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
}
