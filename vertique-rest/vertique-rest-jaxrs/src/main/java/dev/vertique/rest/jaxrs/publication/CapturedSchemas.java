// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication;

import io.vertx.core.json.JsonObject;
import jakarta.annotation.Nullable;
import java.util.Map;

/**
 * INTERNAL: detached deep copies of the schemas a request-validation gate received for one
 * operation, taken before the gate ran. Public only for cross-module use by sibling framework
 * modules; outside the maturity promise and not an application contract.
 *
 * @param body           a deep copy of the request body schema, or {@code null} when the
 *                        operation declares no body
 * @param bodyProvenance the body schema's provenance, held by reference and never inspected by
 *                        rest-jaxrs; {@code null} when {@code body} is {@code null}
 * @param parameters     a deep copy of the schema for every descriptor parameter key that has
 *                        one, keyed by {@link InputKey}
 */
public record CapturedSchemas(
        @Nullable JsonObject body, @Nullable Object bodyProvenance, Map<InputKey, JsonObject> parameters) {

    /**
     * Compact constructor storing an unmodifiable copy of {@code parameters}.
     */
    public CapturedSchemas {
        parameters = Map.copyOf(parameters);
    }
}
