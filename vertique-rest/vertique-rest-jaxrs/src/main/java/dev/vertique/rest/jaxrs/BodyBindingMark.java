// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.Nullable;

/**
 * Records, on the routing context, how the request body of the current request was materialized.
 *
 * <p>A framework binder calls {@link #record} after it bound the body to the declared type, naming
 * the kind of body and the mapper it actually passed. {@link ConstraintViolationMapper} reads the
 * mark to decide whether a bean-validation failure on the body can be reported as a location in the
 * request: a body bound by an application {@code RequestBodyDecoder}, or by anything that never calls
 * {@link #record}, carries no mark and is reported without one.
 */
final class BodyBindingMark {

    /** Routing-context data key under which the binding is stored. */
    static final String KEY = "vertique.rest.jaxrs.bodyBinding";

    /** How a framework binder materialized the body. */
    enum Kind {
        /** A JSON document bound to the declared type; its names are the mapper's wire names. */
        JSON,
        /** Form-urlencoded attributes bound to the declared type; its fields are request parameters. */
        FORM
    }

    /**
     * One body binding.
     *
     * @param kind   how the body was materialized
     * @param mapper the mapper the binder passed to the materialization call
     */
    record Binding(Kind kind, ObjectMapper mapper) {}

    private BodyBindingMark() {}

    /**
     * Records the binding of the current request's body. A later call replaces an earlier one.
     *
     * @param ctx    the current routing context
     * @param kind   how the body was materialized
     * @param mapper the mapper the binder passed to the materialization call
     */
    static void record(RoutingContext ctx, Kind kind, ObjectMapper mapper) {
        ctx.put(KEY, new Binding(kind, mapper));
    }

    /**
     * Returns the binding recorded for the current request.
     *
     * @param ctx the current routing context
     * @return the binding, or {@code null} when no framework binder materialized the body
     */
    @Nullable
    static Binding of(RoutingContext ctx) {
        return ctx.get(KEY) instanceof Binding binding ? binding : null;
    }
}
