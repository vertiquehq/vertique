// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.ops;

/**
 * Observes the operator commands of {@link OutboxOperations}: whether the command's transaction committed or failed,
 * and the security context that was bound when the command was called.
 *
 * <p>Contribute an implementation with {@code @Provides @IntoSet}. Every contributed listener receives every event,
 * synchronously and in no particular order relative to other listeners. A listener must not block. Events carry no
 * payload, headers, last-error text or message text. Nothing is reported to the relay's
 * {@link dev.vertique.inboxoutbox.OutboxPublishObserver}.
 *
 * <p>Callbacks run on the thread that completes the command, which is not ordered relative to the caller's own result
 * future; a refusal before the transaction is reported on the calling thread. A callback must not block that thread.
 *
 * <p>Delivery is at most once: if the process stops between the commit and the notification, the event is not
 * delivered, and the entry's state in the table remains the source of truth.
 *
 * <p>A listener is isolated from the command: an {@link Exception} it throws is logged and swallowed, and does not
 * reach the caller or the other listeners. So are a {@link LinkageError}, an {@link AssertionError} and a
 * {@link StackOverflowError}, exactly as for the relay's publish observers; any other {@link Error} is not caught.
 * A swallowed failure is logged by class name, with the failure itself at debug level only.
 *
 * <p>Both events carry a snapshot of the {@code SecurityContext} bound when the command was called, and {@code null}
 * when none was. A system caller, such as a workflow executor or a job, binds no security context of its own, so its
 * commands are reported with a {@code null} snapshot and are otherwise unchanged; a system caller that wants its
 * commands attributed must bind its own {@code SecurityContext} before calling.
 *
 * <p>The set of operations grows additively: a listener must have a default branch for an
 * {@link OutboxOperationType} it does not know.
 */
public interface OutboxOperationListener {

    /**
     * Receives a command whose transaction committed. It is reported only after the commit is confirmed, and a
     * discard of an entry that was already discarded is still reported, with
     * {@link OutboxEntryChange#changed() changed} {@code false}.
     *
     * <p>An {@link Exception} thrown by this callback is caught, logged, and swallowed; it does not affect the
     * command or the other listeners.
     *
     * @param change the committed command
     */
    void onOutboxEntryChanged(OutboxEntryChange change);

    /**
     * Receives a command that failed: it was refused as invalid, found no entry, found the entry in another state
     * than expected, hit a persistence failure, or its commit failed or could not be confirmed. It is reported once
     * per call, with the same {@link OutboxCommandFailure#callId() callId} the committed report would have carried.
     *
     * <p>An {@link Exception} thrown by this callback is caught, logged, and swallowed; it does not affect the
     * command or the other listeners.
     *
     * @param failure the failed command
     */
    default void onOutboxCommandFailed(OutboxCommandFailure failure) {}
}
