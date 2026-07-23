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
 * {@link PayloadSource} implementation for {@link PayloadKind#BUFFERED} payloads.
 *
 * <p>Backed by a raw {@code byte[]} captured by reference (no defensive copy). The Vert.x
 * {@link Buffer} view is created <em>lazily</em> on the first call to {@link #bufferedView()} via
 * a double-checked-locking {@link #bufferView()} helper. This avoids the allocation on capture
 * paths that never request the {@link Buffer} form: {@link #bufferedStream()} and
 * {@link #copyPrefix(int)} both operate directly on the {@code byte[]} and do not trigger buffer
 * construction (FR-AUD-405).
 *
 * <p>Note: {@link Buffer#buffer(byte[])} in Vert.x 5.0.8 allocates a new heap {@code ByteBuf}
 * and <em>copies</em> the array — the "wraps without copying" claim in older javadoc is incorrect
 * for this version. The lazy pattern ensures this copy is only paid when the {@link Buffer} view
 * is actually requested.
 *
 * <p>Each call to {@link #bufferedStream()} returns a fresh {@code ByteArrayInputStream} cursor
 * over the same array — a cursor, not a copy.
 */
final class BufferedPayloadSource implements PayloadSource {

    // --- Fields ---

    private final byte[] bytes;
    private final String contentType;

    /**
     * Lazily-initialized Vert.x Buffer view of {@link #bytes}.
     *
     * <p>{@link Buffer#buffer(byte[])} copies the array, so creation is deferred to first
     * {@link #bufferedView()} call via double-checked locking (per-instance monitor). Callers
     * that only use {@link #bufferedStream()} or {@link #copyPrefix(int)} incur no Buffer
     * allocation.
     */
    private volatile Buffer lazyBufferView;

    // --- Constructor ---

    /**
     * Creates a buffered payload source backed by the given array.
     *
     * <p>No {@link Buffer} is allocated at construction time; the Buffer view is built lazily
     * on first {@link #bufferedView()} call.
     *
     * @param bytes       the raw bytes; captured by reference, not copied
     * @param contentType the MIME content-type, or {@code null} if unknown
     */
    BufferedPayloadSource(byte[] bytes, String contentType) {
        this.bytes = bytes;
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
        return OptionalLong.of(bytes.length);
    }

    /**
     * Returns a Vert.x {@link Buffer} view of the underlying byte array, creating it lazily on
     * first call (double-checked locking).
     *
     * <p>{@link Buffer#buffer(byte[])} copies the array in Vert.x 5.0.8, so the view is built
     * at most once and reused on subsequent calls.
     *
     * @return an {@link Optional} containing the buffer view; never empty
     */
    @Override
    public Optional<Buffer> bufferedView() {
        return Optional.of(bufferView());
    }

    @Override
    public Optional<InputStream> bufferedStream() {
        // Fresh cursor each call; shares the original array (no copy)
        return Optional.of(new ByteArrayInputStream(bytes));
    }

    @Override
    public byte[] copyPrefix(int maxBytes) {
        int len = Math.min(bytes.length, maxBytes);
        return Arrays.copyOf(bytes, len);
    }

    // --- Lazy Buffer construction ---

    /**
     * Returns the lazily-initialized {@link Buffer} view, building it on first call via
     * double-checked locking (per-instance monitor).
     *
     * @return the Buffer view; never null
     */
    private Buffer bufferView() {
        Buffer b = lazyBufferView;
        if (b == null) {
            synchronized (this) {
                b = lazyBufferView;
                if (b == null) {
                    // Buffer.buffer(byte[]) copies the array in Vert.x 5.0.8; this is intentional
                    // and is exactly why we defer the allocation to first use.
                    b = Buffer.buffer(bytes);
                    lazyBufferView = b;
                }
            }
        }
        return b;
    }
}
