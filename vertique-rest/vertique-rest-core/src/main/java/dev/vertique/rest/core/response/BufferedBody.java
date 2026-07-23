// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.response;

import io.vertx.core.buffer.Buffer;

/**
 * A fully buffered response body produced by a {@link ResponseBodyEncoder}.
 *
 * @param buffer        the encoded body content
 * @param contentType   the encoder-supplied default content type, or {@code null} if the
 *                      {@link jakarta.ws.rs.core.Response} already specifies Content-Type
 * @param contentLength the body length in bytes, or {@code null} to omit Content-Length
 */
public record BufferedBody(Buffer buffer, String contentType, Long contentLength) implements SerializedBody {}
