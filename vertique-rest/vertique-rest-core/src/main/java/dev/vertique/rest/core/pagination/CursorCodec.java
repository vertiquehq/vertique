// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.pagination;

/**
 * SPI for encoding and decoding cursor tokens used in cursor-based pagination.
 *
 * <p>Implementations transform raw backend cursor strings to opaque tokens safe for
 * client-side storage and URL transmission, and back again.
 *
 * <p>The framework provides {@link PlainCursorCodec} as a built-in passthrough implementation
 * (no protection); suitable for development or internal services where token integrity is
 * enforced at the network layer.
 *
 * <p><strong>URL-safety contract:</strong> Production implementations of {@link #encode(String)}
 * SHOULD return a string containing only Base64URL characters ({@code A-Za-z0-9_-}) with
 * no padding ({@code =}). This guarantees tokens are safe in query parameters and RFC 8288
 * Link headers without additional percent-encoding. {@link PlainCursorCodec} is an intentional
 * exception — it passes tokens through unchanged and delegates URL-safety to the backend.
 *
 * <p>No default Dagger binding is provided. Applications that want DI-managed codec
 * injection add their own {@code @Provides CursorCodec} method. Applications that do not
 * need DI can use the convenience methods on {@link CursorPageRequest#decodeCursor()} and
 * {@link CursorPage#of(java.util.List, String, String)} which default to
 * {@link PlainCursorCodec}.
 *
 * <p>Example Dagger wiring with a custom signing codec:
 * <pre>{@code
 * @Provides @Singleton
 * CursorCodec cursorCodec(@VertxConfig JsonObject config) {
 *     return new MySigningCursorCodec(config.getString("cursor.secret"));
 * }
 * }</pre>
 */
public interface CursorCodec {

    /**
     * Encodes a raw backend cursor string to an opaque, URL-safe token for the client.
     *
     * <p>Production implementations SHOULD return only Base64URL characters
     * ({@code A-Za-z0-9_-}, no padding {@code =}) so tokens are safe in query parameters
     * and Link headers. {@link PlainCursorCodec} delegates URL-safety to the backend.
     *
     * @param rawCursor the raw cursor token from the backend (e.g. {@code PageCursor.toToken()})
     * @return an opaque, URL-safe token suitable for returning to the client
     */
    String encode(String rawCursor);

    /**
     * Decodes a client-provided opaque token back to the raw backend cursor string.
     *
     * @param opaqueToken the cursor token received from the client
     * @return the raw cursor string for backend use
     * @throws InvalidCursorException if the token is malformed, tampered with, or expired
     */
    String decode(String opaqueToken) throws InvalidCursorException;
}
