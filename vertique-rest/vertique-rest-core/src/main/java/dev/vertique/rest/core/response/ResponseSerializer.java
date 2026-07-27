// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.response;

import io.vertx.core.Future;
import io.vertx.ext.web.RoutingContext;
import jakarta.ws.rs.core.Response;

/**
 * Serializes a {@link jakarta.ws.rs.core.Response} to the HTTP wire.
 *
 * <p>The default implementation delegates body encoding to {@link ResponseBodyEncoder}
 * instances, selecting the first matching encoder from a priority-sorted list. Encoders
 * are contributed via Dagger {@code Set<ResponseBodyEncoder>} multibinding.
 *
 * <p>After the encoder encodes the entity, all {@link dev.vertique.rest.core.interceptor.RequestInterceptor#onSerialize}
 * hooks are invoked with both the original {@link jakarta.ws.rs.core.Response} and the
 * encoded {@link SerializedBody} for observability.
 *
 * <p>Contributed as a singleton via Dagger; override for entirely custom
 * serialization orchestration.
 */
public interface ResponseSerializer {

    /**
     * Serializes the given Response body to the HTTP wire.
     *
     * <p>Invoked by the response pipeline for responses requiring serializer-owned body
     * handling, on the request's event-loop context, after the status code and headers
     * have been written to the {@code RoutingContext}'s response and after
     * {@code transformResponse} hooks have run. Normal empty-body and bare fallback paths
     * bypass the serializer entirely; the error fail-open path may retry it exactly once
     * after a synchronous pre-initiation failure (FR-JSON-058A). Implementations MUST NOT
     * block the calling thread.
     *
     * <p>Completion contract (dual-channel):
     * <ul>
     *   <li>SYNCHRONOUS THROW — no write or end was initiated (encode-time failure).
     *       The caller may retry against the same response head (fail-open, FR-JSON-058A).</li>
     *   <li>RETURNED FUTURE — success: the response has been fully written and ended.
     *       Failure: the wire write failed after handoff; zero or more bytes may have been
     *       written; never retryable; the caller owns terminal cleanup (the response may
     *       still need ending). The future MAY complete on any thread — callers must not
     *       assume context affinity; the framework pipeline redispatches handling onto the
     *       request context.</li>
     * </ul>
     *
     * <p>Default implementation, per branch: null entity → future of {@code end()};
     * no matching encoder → future of {@code end(problemJson)} [500]; BufferedBody →
     * future of {@code end(buffer)}; StreamingBody →
     * {@code stream.pipe().endOnFailure(false).to(httpResponse)} — MUST NOT buffer
     * (FR-RESTSER-013 / NFR-003) and MUST NOT end the response on pipe failure.
     *
     * @param ctx      the routing context whose response head has already been written
     * @param response the produced framework response carrying the entity to serialize
     * @return a future settling with wire completion per the contract above; never
     *         {@code null}
     */
    Future<Void> serialize(RoutingContext ctx, Response response);
}
