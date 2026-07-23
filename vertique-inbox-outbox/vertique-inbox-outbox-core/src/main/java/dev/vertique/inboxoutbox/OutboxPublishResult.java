// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox;

/**
 * Result returned by an {@link OutboxDestinationHandler} after a publish attempt.
 *
 * <p>The sealed hierarchy models four distinct outcomes:
 * <ul>
 *   <li>{@link Success} — delivery was confirmed; the entry will be marked {@link OutboxEntryState#PUBLISHED}</li>
 *   <li>{@link RetryableFailure} — delivery failed transiently; the entry will be retried with
 *       exponential backoff up to the configured maximum attempts</li>
 *   <li>{@link PermanentFailure} — delivery failed deterministically; the entry will be moved
 *       immediately to {@link OutboxEntryState#DEAD_LETTER} without further retries</li>
 *   <li>{@link Unresolvable} — no handler is registered for the entry's destination; the entry
 *       will be held with a long delay and retried when a handler is eventually registered</li>
 * </ul>
 *
 * <p>Use the static factory methods to construct results:
 * <pre>{@code
 * return OutboxPublishResult.success();
 * return OutboxPublishResult.retryable("upstream timeout", cause);
 * return OutboxPublishResult.permanent("validation failed", cause);
 * return OutboxPublishResult.unresolvable("no handler for: foo/bar");
 * }</pre>
 */
public sealed interface OutboxPublishResult {

    /**
     * Indicates that the outbox entry was delivered successfully.
     */
    record Success() implements OutboxPublishResult {}

    /**
     * Indicates that delivery failed with a transient error and should be retried.
     *
     * @param message human-readable description of the failure
     * @param cause   the exception that caused the failure, or {@code null} if not available
     */
    record RetryableFailure(String message, Throwable cause) implements OutboxPublishResult {}

    /**
     * Indicates that delivery failed with a deterministic error and must not be retried.
     * The entry will be moved directly to {@link OutboxEntryState#DEAD_LETTER}.
     *
     * @param message human-readable description of the failure
     * @param cause   the exception that caused the failure, or {@code null} if not available
     */
    record PermanentFailure(String message, Throwable cause) implements OutboxPublishResult {}

    /**
     * Indicates that no handler is currently registered for the entry's destination.
     * The relay will back off with a long delay and re-attempt after the handler is deployed.
     *
     * @param message human-readable description of why the destination is unresolvable
     */
    record Unresolvable(String message) implements OutboxPublishResult {}

    // --- Static Factories ---

    /**
     * Returns a {@link Success} result indicating successful delivery.
     *
     * @return a {@code Success} instance
     */
    static OutboxPublishResult success() {
        return new Success();
    }

    /**
     * Returns a {@link RetryableFailure} result indicating a transient delivery failure.
     *
     * @param message human-readable description of the failure
     * @param cause   the exception that caused the failure
     * @return a {@code RetryableFailure} instance
     */
    static OutboxPublishResult retryable(String message, Throwable cause) {
        return new RetryableFailure(message, cause);
    }

    /**
     * Returns a {@link PermanentFailure} result indicating a deterministic delivery failure.
     *
     * @param message human-readable description of the failure
     * @param cause   the exception that caused the failure
     * @return a {@code PermanentFailure} instance
     */
    static OutboxPublishResult permanent(String message, Throwable cause) {
        return new PermanentFailure(message, cause);
    }

    /**
     * Returns an {@link Unresolvable} result indicating no handler is registered for the
     * entry's destination.
     *
     * @param message human-readable description of why the destination is unresolvable
     * @return an {@code Unresolvable} instance
     */
    static OutboxPublishResult unresolvable(String message) {
        return new Unresolvable(message);
    }
}
