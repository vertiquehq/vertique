// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.payload;

import io.vertx.core.buffer.Buffer;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * {@link PayloadSource} implementation for {@link PayloadKind#BUFFERED} payloads backed by a
 * {@link java.nio.ByteBuffer}.
 *
 * <p>The constructor stores a {@link java.nio.ByteBuffer#duplicate() duplicate} view of the
 * caller's buffer — {@code duplicate()} copies only the position/limit metadata, not the backing
 * bytes, so construction is always O(1) with no heap allocation beyond the view object. This
 * isolates the source from any position changes the caller makes after construction.
 *
 * <p>The {@code remaining()} count and content-type are captured at construction. The backing
 * {@code byte[]} is materialized lazily on the first call to {@link #bufferedStream()},
 * {@link #copyPrefix(int)}, or {@link #bufferedView()} via a double-checked-locking
 * {@link #bytes()} helper. Subsequent calls return the same array without re-copying.
 *
 * <p>This defers the inherent one-time copy (position-limited view → flat array) to first read,
 * consistent with {@link BufferBackedPayloadSource}'s lazy-extraction pattern.
 */
final class ByteBufferBackedPayloadSource implements PayloadSource {

    // --- Fields ---

    /**
     * A duplicate (view) of the caller's ByteBuffer captured at construction.
     * Position/limit are frozen at construction time; the caller's buffer is independent.
     */
    private final java.nio.ByteBuffer dup;

    /** Number of remaining bytes at construction time; used as the declared length. */
    private final int remaining;

    private final String contentType;

    /**
     * Lazily materialized byte array; populated on first call to {@link #bytes()}.
     * Guarded by the monitor of this instance (DCL pattern).
     */
    private volatile byte[] bytes;

    // --- Constructor ---

    /**
     * Creates a payload source that captures a duplicate view of the given {@link java.nio.ByteBuffer}.
     *
     * <p>{@link java.nio.ByteBuffer#duplicate()} is called immediately; the stored view's
     * position and limit are independent from the caller's buffer from this point forward. The
     * backing array is NOT copied at this point.
     *
     * @param buffer      the source buffer; remaining bytes form the payload; must not be
     *                    {@code null}
     * @param contentType the MIME content-type, or {@code null} if unknown
     */
    ByteBufferBackedPayloadSource(java.nio.ByteBuffer buffer, String contentType) {
        this.dup = buffer.duplicate();
        this.remaining = buffer.remaining();
        this.contentType = contentType;
    }

    // --- PayloadSource ---

    @Override
    public PayloadKind kind() {
        return PayloadKind.BUFFERED;
    }

    @Override
    public Optional<String> contentType() {
        return Optional.ofNullable(contentType);
    }

    @Override
    public OptionalLong declaredLength() {
        return OptionalLong.of(remaining);
    }

    /**
     * Returns a Vert.x {@link Buffer} wrapping the lazily-materialized byte array.
     *
     * <p>The byte array is materialized on the first call (double-checked locking) and reused on
     * subsequent calls.
     *
     * @return an {@link Optional} containing the buffer; never empty
     */
    @Override
    public Optional<Buffer> bufferedView() {
        return Optional.of(Buffer.buffer(bytes()));
    }

    /**
     * Returns a fresh {@link ByteArrayInputStream} over the lazily-materialized byte array,
     * positioned at the start.
     *
     * <p>The byte array is materialized on the first call (double-checked locking). Each call
     * returns a fresh cursor; the underlying array is shared.
     *
     * @return an {@link Optional} containing a fresh stream; never empty
     */
    @Override
    public Optional<InputStream> bufferedStream() {
        return Optional.of(new ByteArrayInputStream(bytes()));
    }

    /**
     * Copies up to {@code maxBytes} leading bytes from the lazily-materialized array.
     *
     * @param maxBytes the maximum number of bytes to copy; must be non-negative
     * @return a byte array of length {@code min(remaining, maxBytes)}
     */
    @Override
    public byte[] copyPrefix(int maxBytes) {
        byte[] b = bytes();
        return Arrays.copyOf(b, Math.min(b.length, maxBytes));
    }

    // --- Lazy byte extraction ---

    /**
     * Returns the materialized byte array, computing it on first call via double-checked locking.
     *
     * <p>Reads from a fresh duplicate of the stored view so the stored view's position is never
     * advanced; the method is therefore idempotent and safe to call concurrently.
     *
     * @return the materialized bytes; never {@code null}
     */
    private byte[] bytes() {
        byte[] b = bytes;
        if (b == null) {
            synchronized (this) {
                b = bytes;
                if (b == null) {
                    // Read from a fresh duplicate so the stored dup's position is untouched,
                    // making this method safe to call multiple times without side effects.
                    java.nio.ByteBuffer read = dup.duplicate();
                    b = new byte[read.remaining()];
                    read.get(b);
                    bytes = b;
                }
            }
        }
        return b;
    }
}
