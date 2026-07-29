// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.runtime.fixture;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.SortedSet;

/**
 * JAX-RS resource fixture declaring a {@code SortedSet<T>} <em>entity body</em> whose element type is
 * not {@link Comparable}. Used by
 * {@link dev.vertique.rest.jaxrs.RouteStartupValidationTest} to prove that the sorted-shape startup
 * guard is scoped to the sources whose values are materialized as a {@code TreeSet}, and therefore
 * leaves a BODY parameter alone.
 *
 * <p>The shape only occurs on the <em>generated</em> dispatch path: the reflective
 * {@code ResourceScanner} hard-codes {@code componentType = null} for BODY, while the codegen
 * resolver resolves the declared {@code List}/{@code Set} element type for BODY as well (a
 * deliberately deferred divergence), so the emitted {@code ParamMeta} carries a component type. The
 * companion {@link SortedSetBodyResource_JaxRsDescriptor} reproduces exactly that emitted shape.
 */
@Path("/sorted-body")
public class SortedSetBodyResource {

    /** Body element type — deliberately NOT {@link Comparable}. */
    public static final class BodyElement {

        /** Public field so the type is a plain JSON-deserializable bean. */
        public String name;
    }

    /**
     * Accepts a JSON array body deserialized into a {@code SortedSet}. A body is deserialized by the
     * body decoders, never materialized element-wise as a {@code TreeSet} by
     * {@code ParameterExtractor.materializeCollection}, so the element type needs no natural ordering.
     *
     * @param body the deserialized body collection
     * @return the element count
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.TEXT_PLAIN)
    public String post(SortedSet<BodyElement> body) {
        return "size=" + body.size();
    }
}
