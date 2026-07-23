// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.sse;

/**
 * Per-channel configuration overrides for an {@link SseChannel}.
 *
 * <p>Pass an instance to {@link SseChannelFactory#create(SseChannelOptions)} to override the
 * application-level defaults from {@link dev.vertique.rest.core.config.SseConfig} for a single
 * channel. Use {@link #defaults()} when no override is needed.
 *
 * @param bufferSize     maximum number of {@link SseEvent} objects that may be held in the
 *                       channel's internal buffer before the
 *                       {@link BufferOverflowPolicy overflow policy} is applied; must be
 *                       {@code >= 1}
 * @param overflowPolicy behavior when the buffer is full and a new event is submitted via
 *                       {@link SseChannel#send(SseEvent)}; must not be {@code null}
 */
public record SseChannelOptions(int bufferSize, BufferOverflowPolicy overflowPolicy) {

    /**
     * Compact constructor that validates the buffer size is at least 1.
     *
     * @throws IllegalArgumentException if {@code bufferSize} is less than 1
     */
    public SseChannelOptions {
        if (bufferSize < 1) {
            throw new IllegalArgumentException("bufferSize must be >= 1, got: " + bufferSize);
        }
        java.util.Objects.requireNonNull(overflowPolicy, "overflowPolicy must not be null");
    }

    /**
     * Returns a set of options with sensible defaults: a buffer of 256 events and the
     * {@link BufferOverflowPolicy#FAIL} overflow policy.
     *
     * @return default {@link SseChannelOptions} instance
     */
    public static SseChannelOptions defaults() {
        return new SseChannelOptions(256, BufferOverflowPolicy.FAIL);
    }
}
