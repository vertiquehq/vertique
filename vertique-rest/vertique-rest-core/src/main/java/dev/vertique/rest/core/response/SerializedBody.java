// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.response;

/**
 * Represents the encoded output of a {@link ResponseBodyEncoder}.
 *
 * <p>A {@code SerializedBody} is either a {@link BufferedBody} (fully encoded in memory)
 * or a {@link StreamingBody} (piped to the wire without buffering). The
 * {@link ResponseSerializer} dispatches the body to the HTTP response based on this type.
 */
public sealed interface SerializedBody permits BufferedBody, StreamingBody {}
