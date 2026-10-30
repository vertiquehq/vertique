// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.history;

/**
 * Observes what the workflow engine records in an instance's history, after it is committed.
 *
 * <p>Contribute an implementation with {@code @Provides @IntoSet}. Every contributed listener receives every event,
 * synchronously and in no particular order relative to other listeners. A listener must not block. Events carry no
 * payload, state or failure message.
 *
 * <p>Committed events are delivered on the thread that completes the post-commit read of the committed entries, which
 * is not ordered relative to the caller's own result future; a failed action is reported on the thread where the
 * failure was observed. A callback must not block that thread.
 *
 * <p>Delivery is at most once: if the process stops between the commit and the notification, the event is not
 * delivered, and the instance history remains the source of truth.
 *
 * <p>Delivery is not ordered across commits: two successive commits of one instance are read on independent
 * connections, so the second can be delivered before the first, here and on another node. Every event carries its
 * {@code sequence}; a consumer that needs the order sorts by it. A very large commit is delivered as several
 * consecutive {@link WorkflowHistoryCommit}s, each in sequence order. Two adjacent actions on one instance in one
 * transaction give one commit.
 *
 * <p>A listener is isolated from the engine: an {@link Exception} it throws is logged at warn with its stack and does
 * not reach the action or the other listeners. An {@link Error} is not caught and ends the delivery to the listeners
 * after it.
 */
public interface WorkflowHistoryListener {

    /**
     * Receives the history events one committed transaction wrote for an instance.
     * An {@link Exception} thrown by this callback is caught, logged, and swallowed; it does not affect the
     * enclosing operation or the other listeners. An {@link Error} is not caught.
     *
     * @param commit the committed events
     */
    void onHistoryCommitted(WorkflowHistoryCommit commit);

    /**
     * Receives an action that failed, so none of its entries were committed. It is reported once per attempt, on the
     * thread where the failure was observed, even if the caller later recovers and commits the rest of the
     * transaction. A call nested in another call for the same instance in one transaction is not reported separately.
     * An {@link Exception} thrown by this callback is caught, logged, and swallowed; it does not affect the
     * enclosing operation or the other listeners. An {@link Error} is not caught.
     *
     * @param failure the failed action
     */
    default void onHistoryFailed(WorkflowHistoryFailure failure) {}
}
