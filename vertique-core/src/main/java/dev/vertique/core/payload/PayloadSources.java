// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.payload;

import io.vertx.core.buffer.Buffer;
import jakarta.annotation.Nullable;
import java.nio.ByteBuffer;

/**
 * Static factory for creating {@link PayloadSource} instances.
 *
 * <p>Use the methods on this class to construct the three kinds of payload source:
 *
 * <ul>
 *   <li>{@link #absent()} — no body present.
 *   <li>{@link #buffered(byte[], String)}, {@link #buffered(Buffer, String)},
 *       {@link #buffered(ByteBuffer, String)} — full payload held in memory.
 *   <li>{@link #streaming(String, long)} — payload arrives as a stream.
 * </ul>
 *
 * <h2>No-copy guarantee</h2>
 *
 * <p>The {@code buffered(byte[], ...)} overload captures the array by reference — no defensive
 * copy is made. {@link PayloadSource#bufferedStream()} returns a {@code ByteArrayInputStream}
 * cursor over the same array. Callers that require stable bytes must copy before calling the
 * factory.
 */
public final class PayloadSources {

    private PayloadSources() {}

    // --- Factory methods ---

    /**
     * Returns the singleton {@link PayloadKind#ABSENT} source representing a missing body.
     *
     * @return the absent payload source; never {@code null}
     */
    public static PayloadSource absent() {
        return AbsentPayloadSource.INSTANCE;
    }

    /**
     * Creates a {@link PayloadKind#BUFFERED} source backed by the given byte array.
     *
     * <p>The array is captured by reference — no defensive copy is performed. Mutations to
     * {@code bytes} after this call are visible through the returned source's
     * {@link PayloadSource#bufferedStream()} cursor. Callers that require immutable bytes must
     * copy before calling this method.
     *
     * @param bytes       the raw payload bytes; must not be {@code null}
     * @param contentType the MIME content-type, or {@code null} if unknown
     * @return a buffered payload source; never {@code null}
     */
    public static PayloadSource buffered(byte[] bytes, @Nullable String contentType) {
        return new BufferedPayloadSource(bytes, contentType);
    }

    /**
     * Creates a {@link PayloadKind#BUFFERED} source backed by the given Vert.x {@link Buffer}.
     *
     * <p>{@link PayloadSource#bufferedView()} returns the original {@link Buffer} instance
     * unchanged. The underlying bytes are extracted once at construction time for use by
     * {@link PayloadSource#bufferedStream()} and {@link PayloadSource#copyPrefix(int)}.
     *
     * @param buffer      the Vert.x buffer; must not be {@code null}
     * @param contentType the MIME content-type, or {@code null} if unknown
     * @return a buffered payload source backed by the buffer; never {@code null}
     */
    public static PayloadSource buffered(Buffer buffer, @Nullable String contentType) {
        return new BufferBackedPayloadSource(buffer, contentType);
    }

    /**
     * Creates a {@link PayloadKind#BUFFERED} source backed by the remaining bytes of the given
     * {@link ByteBuffer}.
     *
     * <p>A {@link ByteBuffer#duplicate() duplicate} view is captured at construction — a
     * position/limit copy that does NOT copy the backing bytes. The declared length
     * ({@link PayloadSource#declaredLength()}) reflects the {@code remaining()} count at call
     * time. The backing {@code byte[]} is materialized lazily on the first call to
     * {@link PayloadSource#bufferedStream()}, {@link PayloadSource#copyPrefix}, or
     * {@link PayloadSource#bufferedView()}, and reused on subsequent calls.
     *
     * <p>The source is isolated from position changes the caller makes to {@code bytes} after
     * this method returns: the stored duplicate's position/limit are independent from the caller's
     * buffer.
     *
     * @param bytes       the {@link ByteBuffer} whose remaining bytes form the payload; must not
     *                    be {@code null}
     * @param contentType the MIME content-type, or {@code null} if unknown
     * @return a buffered payload source; never {@code null}
     */
    public static PayloadSource buffered(ByteBuffer bytes, @Nullable String contentType) {
        return new ByteBufferBackedPayloadSource(bytes, contentType);
    }

    /**
     * Creates a {@link PayloadKind#STREAMING} source with the given declared length hint.
     *
     * <p>No bytes are buffered. {@link PayloadSource#bufferedView()} and
     * {@link PayloadSource#bufferedStream()} always return empty. {@link PayloadSource#copyPrefix}
     * always returns a zero-length array.
     *
     * @param contentType    the MIME content-type, or {@code null} if unknown
     * @param declaredLength the declared byte length (e.g. from {@code Content-Length})
     * @return a streaming payload source; never {@code null}
     */
    public static PayloadSource streaming(@Nullable String contentType, long declaredLength) {
        return new StreamingPayloadSource(contentType, declaredLength);
    }
}
