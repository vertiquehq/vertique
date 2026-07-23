// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.payload;

import io.vertx.core.buffer.Buffer;
import java.io.InputStream;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Transport-agnostic descriptor for an HTTP request or response body.
 *
 * <p>A {@code PayloadSource} describes whether a payload is absent, buffered in memory, or
 * arriving as a stream, and provides uniform access to its content-type, declared byte length, and
 * underlying bytes (where available).
 *
 * <h2>Kind semantics</h2>
 *
 * <ul>
 *   <li>{@link PayloadKind#ABSENT} — no body; all byte accessors return empty.
 *   <li>{@link PayloadKind#BUFFERED} — full payload is in memory; {@link #bufferedView()} and
 *       {@link #bufferedStream()} are both present.
 *   <li>{@link PayloadKind#STREAMING} — bytes arrive as a stream; {@link #declaredLength()} may
 *       carry the {@code Content-Length} hint but no bytes are buffered locally.
 * </ul>
 *
 * <h2>No-copy contract</h2>
 *
 * <p>Factory methods in {@link PayloadSources} do NOT defensive-copy the input bytes.
 * {@link #bufferedStream()} over a {@code byte[]}-backed source returns a cursor
 * ({@code ByteArrayInputStream}) that shares the original array. Callers that require
 * immutability must copy before calling the factory.
 *
 * @see PayloadSources
 * @see PayloadKind
 */
public interface PayloadSource {

    /**
     * Returns the discriminant describing how the payload bytes are available.
     *
     * @return the payload kind; never {@code null}
     */
    PayloadKind kind();

    /**
     * Returns the MIME content-type of the payload, if known.
     *
     * @return the content-type string, or empty if none was declared
     */
    Optional<String> contentType();

    /**
     * Returns the declared byte length of the payload, if known.
     *
     * <p>For {@link PayloadKind#BUFFERED} sources this equals the actual buffer length.
     * For {@link PayloadKind#STREAMING} this reflects the {@code Content-Length} hint passed at
     * construction. For {@link PayloadKind#ABSENT} this is always empty.
     *
     * @return the declared length in bytes, or empty if unknown
     */
    OptionalLong declaredLength();

    /**
     * Returns a Vert.x {@link Buffer} view of this payload, if available.
     *
     * <p>Present only for {@link PayloadKind#BUFFERED} sources. <strong>Zero-copy is guaranteed only
     * for {@code Buffer}-backed sources</strong> ({@code PayloadSources.buffered(Buffer, …)}), which
     * return their underlying instance. For {@code byte[]}- and {@code ByteBuffer}-backed sources the
     * {@code Buffer} view is <em>lazily materialized and copies</em> on first call (Vert.x
     * {@code Buffer.buffer(byte[])} allocates and copies) — so a hot-path caller that only needs to
     * read the bytes should prefer {@link #bufferedStream()} (a no-copy cursor over the backing bytes)
     * over {@code bufferedView()}.
     *
     * @return the buffer view, or empty for {@link PayloadKind#STREAMING} and {@link PayloadKind#ABSENT}
     */
    Optional<Buffer> bufferedView();

    /**
     * Returns an {@link InputStream} cursor over the buffered bytes, if available.
     *
     * <p>Present only for {@link PayloadKind#BUFFERED} sources. For {@code byte[]}-backed sources
     * this is a {@code ByteArrayInputStream} sharing the original array (no copy). Each call
     * returns a fresh cursor positioned at the start of the array.
     *
     * @return an input stream over the buffered bytes, or empty for non-buffered sources
     */
    Optional<InputStream> bufferedStream();

    /**
     * Returns a bounded copy of the first {@code maxBytes} bytes of this payload.
     *
     * <p>Returns {@code min(size, maxBytes)} bytes for {@link PayloadKind#BUFFERED} sources.
     * Returns a zero-length array for {@link PayloadKind#ABSENT} and {@link PayloadKind#STREAMING}
     * sources (no bytes are locally buffered). Intended for diagnostics and logging probes only —
     * callers must not use this method on the hot path.
     *
     * @param maxBytes the maximum number of bytes to copy; must be non-negative
     * @return a fresh byte array of at most {@code maxBytes} bytes; never {@code null}
     */
    byte[] copyPrefix(int maxBytes);
}
