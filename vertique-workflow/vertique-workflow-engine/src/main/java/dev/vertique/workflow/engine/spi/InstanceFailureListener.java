// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.spi;

import dev.vertique.workflow.ops.WorkflowInstanceId;
import io.vertx.core.Future;

/**
 * Called by the command repository inside the transaction that fails an instance on behalf of a command (a failure
 * disposition, or a claim that dead-letters a {@code START}), after the instance row and its history entry are
 * written, so the engine can add what a failed instance carries beyond those rows, such as its lifecycle event, in the
 * same transaction.
 *
 * <p>INTERNAL: framework wiring between the executor and the command repository.
 *
 * @param <TX> the transaction-handle type
 */
@FunctionalInterface
public interface InstanceFailureListener<TX> {

    /**
     * Handles the failure of an instance.
     *
     * @param instance        the failed instance
     * @param historySequence the sequence of the history entry that records the failure
     * @param reasonCode      the reason code stored on the instance
     * @param message         the sanitized reason stored on the instance
     * @param tx              the transaction that fails the instance; a failed future rolls it back
     * @return a future that completes when the listener's writes are done
     */
    Future<Void> onInstanceFailed(
            WorkflowInstanceId instance, long historySequence, String reasonCode, String message, TX tx);

    /**
     * A listener that does nothing.
     *
     * @param <TX> the transaction-handle type
     * @return the listener
     */
    static <TX> InstanceFailureListener<TX> none() {
        return (instance, historySequence, reasonCode, message, tx) -> Future.succeededFuture();
    }
}
