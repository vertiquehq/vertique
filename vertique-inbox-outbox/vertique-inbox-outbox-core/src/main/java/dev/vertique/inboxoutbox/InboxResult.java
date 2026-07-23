// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox;

/**
 * Result returned by {@link InboxService#processOnce} after attempting to process an inbound
 * message exactly once.
 *
 * <p>The sealed hierarchy models two outcomes:
 * <ul>
 *   <li>{@link Processed} — the message was processed for the first time and carries the
 *       result produced by the supplied work function</li>
 *   <li>{@link Duplicate} — the message was already processed; the work function was not
 *       invoked and no result is available</li>
 * </ul>
 *
 * <p>Callers can branch on the result type with pattern matching:
 * <pre>{@code
 * InboxResult<MyResponse> result = await(inboxService.processOnce(id, source, tx, work));
 * return switch (result) {
 *     case InboxResult.Processed<MyResponse> p -> p.value();
 *     case InboxResult.Duplicate<MyResponse> ignored -> cachedResponse();
 * };
 * }</pre>
 *
 * @param <T> the type of value produced by the work function when the message is new
 */
public sealed interface InboxResult<T> {

    /**
     * Indicates that the message was new and successfully processed.
     *
     * @param <T>   the type of the result value
     * @param value the value returned by the work function
     */
    record Processed<T>(T value) implements InboxResult<T> {}

    /**
     * Indicates that the message was a duplicate and was not processed again.
     *
     * @param <T> the type that would have been produced by the work function
     */
    record Duplicate<T>() implements InboxResult<T> {}

    // --- Convenience Methods ---

    /**
     * Returns {@code true} if this result represents a successfully processed message.
     *
     * @return {@code true} when this is a {@link Processed} result
     */
    default boolean isProcessed() {
        return this instanceof Processed;
    }

    /**
     * Returns {@code true} if this result represents a duplicate message that was skipped.
     *
     * @return {@code true} when this is a {@link Duplicate} result
     */
    default boolean isDuplicate() {
        return this instanceof Duplicate;
    }

    /**
     * Returns the value from a processed result.
     *
     * @return the value produced by the work function
     * @throws IllegalStateException if this is a {@link Duplicate} result
     */
    default T value() {
        if (this instanceof Processed<T> p) {
            return p.value();
        }
        throw new IllegalStateException("Cannot get value from a duplicate InboxResult");
    }
}
