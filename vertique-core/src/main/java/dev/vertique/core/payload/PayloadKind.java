// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.payload;

/**
 * Discriminant describing how the bytes of a {@link PayloadSource} are available.
 *
 * <p>The kind determines which accessors on {@link PayloadSource} return non-empty values:
 *
 * <ul>
 *   <li>{@link #ABSENT} — no body is present; all byte accessors return empty.
 *   <li>{@link #BUFFERED} — the full payload is held in memory; both {@code bufferedView()} and
 *       {@code bufferedStream()} are available.
 *   <li>{@link #STREAMING} — the payload arrives as a stream; {@code declaredLength()} reflects
 *       the {@code Content-Length} hint (if any) but no bytes are buffered.
 * </ul>
 */
public enum PayloadKind {
    /** No body is present; all byte accessors return empty. */
    ABSENT,

    /** The full payload is held in memory; buffered byte access is available. */
    BUFFERED,

    /**
     * The payload arrives as a stream; {@code declaredLength()} may be set but no bytes are
     * buffered.
     */
    STREAMING
}
