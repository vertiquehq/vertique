// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.customresponse;

import dev.vertique.rest.core.interceptor.RequestInterceptor;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.Json;
import io.vertx.ext.web.RoutingContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;

/**
 * Request/response interceptor that validates and computes SHA-256 digest headers.
 *
 * <p>Before request: validates the {@code Digest} request header against the SHA-256 of
 * the raw request body bytes. Returns a failed future if the digest does not match.
 *
 * <p>On response transform: serializes the entity to JSON, computes the SHA-256 digest, sets
 * the {@code Digest} response header, and replaces the entity with a pre-serialized
 * {@link Buffer} to avoid double serialization.
 */
public class DigestFilter implements RequestInterceptor {

    /**
     * Constructs a new {@code DigestFilter}.
     */
    @Inject
    public DigestFilter() {}

    /**
     * Validates the {@code Digest} request header against the SHA-256 of the request body bytes.
     * Returns a failed future if the algorithm is unsupported or the digest does not match.
     * Passes through immediately when no {@code Digest} header is present.
     *
     * @param rc the Vert.x {@link RoutingContext} for the incoming request
     * @return a succeeded future to continue, or a failed future on digest mismatch
     */
    @Override
    public Future<Void> beforeRequest(RoutingContext rc) {
        String digestHeader = rc.request().getHeader("Digest");
        if (digestHeader == null) {
            return Future.succeededFuture();
        }

        if (!digestHeader.startsWith("sha-256=")) {
            return Future.failedFuture(new IllegalArgumentException("Unsupported digest algorithm, expected sha-256"));
        }

        String expectedDigest = digestHeader.substring("sha-256=".length());
        Buffer body = rc.body() != null ? rc.body().buffer() : null;
        byte[] bodyBytes = body != null ? body.getBytes() : new byte[0];
        String actualDigest = DigestUtil.sha256Base64(bodyBytes);
        if (!expectedDigest.equals(actualDigest)) {
            return Future.failedFuture(new IllegalArgumentException("Request body digest mismatch"));
        }

        return Future.succeededFuture();
    }

    /**
     * Computes the SHA-256 digest of the serialized response entity and sets the {@code Digest}
     * response header. Replaces the entity with a pre-serialized {@link Buffer} to avoid double
     * serialization downstream. Returns the response unchanged when the entity is {@code null}
     * or already a {@link Buffer}.
     *
     * @param rc       the Vert.x {@link RoutingContext}
     * @param response the response to transform
     * @return a succeeded future containing the transformed response
     */
    @Override
    public Future<Response> transformResponse(RoutingContext rc, Response response) {
        Object entity = response.getEntity();
        if (entity == null) {
            return Future.succeededFuture(response);
        }
        if (entity instanceof Buffer) {
            return Future.succeededFuture(response);
        }

        String json = Json.encode(entity);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        String digest = DigestUtil.sha256Base64(bytes);

        return Future.succeededFuture(Response.fromResponse(response)
                .header("Digest", "sha-256=" + digest)
                .header("Content-Type", "application/json")
                .entity(Buffer.buffer(bytes))
                .build());
    }
}
