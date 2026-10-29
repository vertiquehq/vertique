// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.history;

/**
 * Observes what the workflow engine records in an instance's history, after it is committed.
 *
 * <p>Contribute an implementation with {@code @Provides @IntoSet}. Every contributed listener receives every event,
 * synchronously and in no particular order relative to other listeners, on the thread of the transaction's
 * completion. A listener must not block. Events carry no payload, state or failure message.
 *
 * <p>Callbacks run on the thread that completes the operation, so a callback must not block that thread.
 *
 * <p>Delivery is at most once: if the process stops between the commit and the notification, the event is not
 * delivered, and the instance history remains the source of truth.
 */
public interface WorkflowHistoryListener {

    /**
     * Receives the history events one committed transaction wrote for an instance.
     * Exceptions thrown by this callback are caught, logged, and swallowed; they do not affect the
     * enclosing operation.
     *
     * @param commit the committed events
     */
    void onHistoryCommitted(WorkflowHistoryCommit commit);

    /**
     * Receives an action that failed, so none of its entries were committed.
     * Exceptions thrown by this callback are caught, logged, and swallowed; they do not affect the
     * enclosing operation.
     *
     * @param failure the failed action
     */
    default void onHistoryFailed(WorkflowHistoryFailure failure) {}
}
