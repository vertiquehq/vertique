// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.response;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;

/**
 * A streaming response body produced by a {@link ResponseBodyEncoder}.
 *
 * <p>The stream is piped directly to the HTTP response without buffering the full payload.
 *
 * @param stream        the stream to pipe to the HTTP response
 * @param contentType   the encoder-supplied default content type, or {@code null} if the
 *                      {@link jakarta.ws.rs.core.Response} already specifies Content-Type
 * @param contentLength the body length in bytes, or {@code null} to omit Content-Length
 */
public record StreamingBody(ReadStream<Buffer> stream, String contentType, Long contentLength)
        implements SerializedBody {}
