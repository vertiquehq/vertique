// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.sse;

/**
 * Defines the slow-consumer behavior when an {@link SseChannel}'s internal event buffer is full.
 *
 * <p>When a producer calls {@link SseChannel#send(SseEvent)} faster than the framework can flush
 * bytes to the HTTP response, the channel buffer eventually fills up. This policy governs what
 * happens at that point: either reject the write immediately so the producer can back off, or
 * discard the oldest buffered event to make room for the new one.
 *
 * <p>The appropriate choice depends on the use case:
 * <ul>
 *   <li>Use {@link #FAIL} for financial tickers or other streams where no event should be silently
 *       lost — the producer must slow down or close the connection.</li>
 *   <li>Use {@link #DROP_OLDEST} for live dashboards where showing the latest value matters more
 *       than perfect delivery, accepting that lagging clients see gaps.</li>
 * </ul>
 */
public enum BufferOverflowPolicy {

    /**
     * Returns a failed {@link io.vertx.core.Future} when the buffer is full. The new event is
     * not enqueued and the producer is responsible for slowing down or closing the channel.
     */
    FAIL,

    /**
     * Silently discards the oldest buffered event to make room for the new one. The
     * {@link SseChannel#send} future succeeds regardless. Use when recency matters more than
     * completeness.
     */
    DROP_OLDEST
}
