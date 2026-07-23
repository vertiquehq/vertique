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
 * Vert.x {@link Buffer}.
 *
 * <p>{@link #bufferedView()} returns the original {@link Buffer} unchanged (no copy). The
 * underlying {@code byte[]} is extracted lazily via {@link Buffer#getBytes()} only on the first
 * call to {@link #bufferedStream()} or {@link #copyPrefix(int)} — callers that only use
 * {@link #bufferedView()} incur no extraction cost (FR-AUD-405).
 */
final class BufferBackedPayloadSource implements PayloadSource {

    // --- Fields ---

    private final Buffer buffer;
    private final String contentType;
    // Byte array extracted lazily: populated on first bufferedStream() / copyPrefix() call.
    // Guarded by the monitor of this instance (DCL pattern).
    private volatile byte[] bytes;

    // --- Constructor ---

    /**
     * Creates a payload source backed by the given Vert.x {@link Buffer}.
     *
     * @param buffer      the Vert.x buffer; must not be {@code null}
     * @param contentType the MIME content-type, or {@code null} if unknown
     */
    BufferBackedPayloadSource(Buffer buffer, String contentType) {
        this.buffer = buffer;
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
        return OptionalLong.of(buffer.length());
    }

    /**
     * Returns the original {@link Buffer} with no copy.
     *
     * @return an {@link Optional} containing the original buffer; never empty
     */
    @Override
    public Optional<Buffer> bufferedView() {
        return Optional.of(buffer);
    }

    /**
     * Returns a fresh {@link ByteArrayInputStream} over the extracted byte array, materializing
     * the array lazily on first call (double-checked locking).
     *
     * @return an {@link Optional} containing a fresh stream positioned at the start; never empty
     */
    @Override
    public Optional<InputStream> bufferedStream() {
        return Optional.of(new ByteArrayInputStream(bytes()));
    }

    /**
     * Copies up to {@code maxBytes} leading bytes from the buffer, materializing the byte array
     * lazily on first call.
     *
     * @param maxBytes the maximum number of bytes to copy
     * @return a byte array of length {@code min(buffer.length(), maxBytes)}
     */
    @Override
    public byte[] copyPrefix(int maxBytes) {
        byte[] b = bytes();
        int len = Math.min(b.length, maxBytes);
        return Arrays.copyOf(b, len);
    }

    // --- Lazy byte extraction ---

    /**
     * Returns the byte array representation of the buffer, materializing it on first call
     * (double-checked locking, per-instance monitor).
     *
     * @return the extracted bytes; never null
     */
    private byte[] bytes() {
        byte[] b = bytes;
        if (b == null) {
            synchronized (this) {
                b = bytes;
                if (b == null) {
                    b = buffer.getBytes();
                    bytes = b;
                }
            }
        }
        return b;
    }
}
