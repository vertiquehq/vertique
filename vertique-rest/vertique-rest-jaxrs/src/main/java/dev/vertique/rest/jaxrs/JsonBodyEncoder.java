// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.rest.core.response.BufferedBody;
import dev.vertique.rest.core.response.ResponseBodyEncoder;
import dev.vertique.rest.core.response.SerializedBody;
import dev.vertique.rest.jaxrs.request.BoundRequest;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.EncodeException;
import io.vertx.core.json.Json;
import io.vertx.ext.web.RoutingContext;
import jakarta.ws.rs.core.Response;

/**
 * Fallback {@link ResponseBodyEncoder} that serializes entities to JSON.
 *
 * <p>The JSON string is produced through the per-method <em>resolved profile mapper</em> when one is
 * present: if the request path stashed an {@link ObjectMapper} on the routing context under
 * {@link BoundRequest#KEY_RESOLVED_BODY_MAPPER} (a non-{@code vertx} {@code @JsonProfile} resolved for
 * the method, or the configured default), the success entity is serialized via that mapper so the
 * response is symmetric with the request binding ({@code FR-JSON-055/056}). When the stash is absent
 * (the {@code vertx} default — no profile and no configured default), serialization stays byte-for-byte
 * on {@link Json#encode} ({@code FR-JSON-057}). Either way the Content-Type defaults to
 * {@code application/json}.
 *
 * <p>This encoder accepts entities when the effective Content-Type is either unset ({@code null}) or
 * JSON-compatible (contains {@code "json"}, e.g. {@code application/json},
 * {@code application/problem+json}). Non-JSON content types are rejected so that a mismatched
 * {@code .type("application/xml")} on a {@link jakarta.ws.rs.core.Response} is surfaced as a 500 rather
 * than silently sending JSON with the wrong Content-Type header.
 *
 * <p>The framework registers it at priority {@code 1100} to ensure it loses to all
 * other framework defaults (priority {@code 1000}). Application encoders at the
 * default priority ({@code 0}) always take precedence over this encoder.
 *
 * <p>Priority: {@code 1100} (fallback).
 */
class JsonBodyEncoder implements ResponseBodyEncoder {

    @Override
    public int priority() {
        return 1100;
    }

    /**
     * Returns {@code true} when the content type is unset or JSON-compatible.
     *
     * @param entityType  the runtime class of the response entity (ignored)
     * @param contentType the effective Content-Type, or {@code null} if unset
     * @return {@code true} if {@code contentType} is {@code null} or contains {@code "json"}
     */
    @Override
    public boolean canEncode(Class<?> entityType, String contentType) {
        return contentType == null || contentType.contains("json");
    }

    /**
     * Serializes the entity to a JSON string and wraps it in a {@link BufferedBody} with
     * {@code application/json} as the default content type.
     *
     * <p>The JSON string is produced by the per-method resolved profile mapper when one is stashed on
     * {@code ctx} under {@link BoundRequest#KEY_RESOLVED_BODY_MAPPER} (present ⇒ resolved profile
     * mapper); otherwise it falls back to {@link Json#encode} (absent ⇒ the byte-for-byte {@code vertx}
     * path). A serialization failure of the success entity propagates as an unchecked
     * {@link EncodeException} (matching how {@link Json#encode} surfaces an encode failure), so it
     * becomes a 500 — it is never swallowed.
     *
     * @param ctx      the current routing context
     * @param response the full JAX-RS response
     * @param entity   the non-null entity to encode
     * @return a {@link BufferedBody} containing the JSON-encoded entity
     * @throws EncodeException if the resolved profile mapper fails to serialize {@code entity}
     */
    @Override
    public SerializedBody encode(RoutingContext ctx, Response response, Object entity) {
        ObjectMapper m = ctx.get(BoundRequest.KEY_RESOLVED_BODY_MAPPER);
        String json = m != null ? writeViaProfile(m, entity) : Json.encode(entity);
        return new BufferedBody(Buffer.buffer(json), "application/json", null);
    }

    /**
     * Serializes {@code entity} via the resolved profile {@code mapper}, wrapping the checked
     * {@link JsonProcessingException} as an unchecked {@link EncodeException} so a success-entity
     * serialization failure surfaces exactly like a {@link Json#encode} failure (and becomes a 500).
     *
     * @param mapper the resolved per-method profile mapper
     * @param entity the non-null entity to serialize
     * @return the JSON string produced by {@code mapper}
     * @throws EncodeException if {@code mapper} cannot serialize {@code entity}
     */
    private static String writeViaProfile(ObjectMapper mapper, Object entity) {
        try {
            return mapper.writeValueAsString(entity);
        } catch (JsonProcessingException e) {
            throw new EncodeException("Failed to encode as JSON: " + e.getMessage(), e);
        }
    }
}
