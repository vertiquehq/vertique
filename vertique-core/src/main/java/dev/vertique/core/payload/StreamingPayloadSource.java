// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.payload;

import io.vertx.core.buffer.Buffer;
import java.io.InputStream;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * {@link PayloadSource} implementation for {@link PayloadKind#STREAMING} payloads.
 *
 * <p>No bytes are locally buffered. {@link #bufferedView()} and {@link #bufferedStream()} always
 * return empty. {@link #declaredLength()} reflects the {@code Content-Length} hint supplied at
 * construction time.
 */
final class StreamingPayloadSource implements PayloadSource {

    // --- Fields ---

    /** Shared empty byte array returned by {@link #copyPrefix(int)} — avoids per-call allocation. */
    private static final byte[] EMPTY = new byte[0];

    private final String contentType;
    private final long declaredLength;

    // --- Constructor ---

    /**
     * Creates a streaming payload source.
     *
     * @param contentType    the MIME content-type, or {@code null} if unknown
     * @param declaredLength the declared byte length (e.g. from {@code Content-Length}); use a
     *                       negative value to indicate unknown length
     */
    StreamingPayloadSource(String contentType, long declaredLength) {
        this.contentType = contentType;
        this.declaredLength = declaredLength;
    }

    // --- PayloadSource ---

    @Override
    public PayloadKind kind() {
        return PayloadKind.STREAMING;
    }

    @Override
    public Optional<String> contentType() {
        return Optional.ofNullable(contentType);
    }

    @Override
    public OptionalLong declaredLength() {
        return declaredLength < 0 ? OptionalLong.empty() : OptionalLong.of(declaredLength);
    }

    @Override
    public Optional<Buffer> bufferedView() {
        return Optional.empty();
    }

    @Override
    public Optional<InputStream> bufferedStream() {
        return Optional.empty();
    }

    @Override
    public byte[] copyPrefix(int maxBytes) {
        return EMPTY;
    }
}
