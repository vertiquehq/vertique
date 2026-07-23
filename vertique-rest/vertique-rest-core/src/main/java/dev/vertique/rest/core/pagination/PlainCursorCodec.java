// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.pagination;

/**
 * No-op {@link CursorCodec} that passes cursor tokens through unchanged.
 *
 * <p>{@link #encode(String)} returns the raw token as-is; {@link #decode(String)}
 * returns the opaque token as-is. When used with db-core {@code PageCursor.toToken()},
 * the resulting tokens are already Base64URL-encoded without padding — satisfying the
 * URL-safety contract.
 *
 * <p><strong>Warning:</strong> This codec provides NO tamper protection. A client can
 * craft arbitrary cursor tokens, potentially manipulating page size, navigation direction,
 * or keyset values. Use a signing {@link CursorCodec} implementation in production when
 * cursor integrity matters.
 */
public class PlainCursorCodec implements CursorCodec {

    /** Shared singleton instance — use instead of {@code new PlainCursorCodec()}. */
    public static final PlainCursorCodec INSTANCE = new PlainCursorCodec();

    /**
     * Returns {@code rawCursor} unchanged.
     *
     * @param rawCursor the raw cursor token
     * @return {@code rawCursor} as-is
     */
    @Override
    public String encode(String rawCursor) {
        return rawCursor;
    }

    /**
     * Returns {@code opaqueToken} unchanged.
     *
     * @param opaqueToken the cursor token from the client
     * @return {@code opaqueToken} as-is
     */
    @Override
    public String decode(String opaqueToken) {
        return opaqueToken;
    }
}
