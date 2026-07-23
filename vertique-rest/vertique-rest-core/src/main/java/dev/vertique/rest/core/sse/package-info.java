// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Server-Sent Events (SSE) contracts for the REST layer.
 *
 * <p>The primary endpoint contract is a returned {@link dev.vertique.rest.core.sse.SseChannel}-backed
 * {@code ReadStream<SseEvent>} that the framework serializes to the SSE wire format.
 * Application code creates channels via {@link dev.vertique.rest.core.sse.SseChannelFactory}
 * and returns {@link dev.vertique.rest.core.sse.SseChannel#stream()} from JAX-RS resource methods
 * annotated with {@code @Produces("text/event-stream")}.
 */
package dev.vertique.rest.core.sse;
