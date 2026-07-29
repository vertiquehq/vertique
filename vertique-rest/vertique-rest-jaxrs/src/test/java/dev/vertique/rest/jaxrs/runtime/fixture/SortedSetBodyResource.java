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
 * guard is <b>scoped</b> to the sources whose values {@code ParameterExtractor} materializes as a
 * {@code TreeSet}, and therefore leaves a BODY parameter to the selected {@code RequestBodyDecoder}.
 *
 * <p><b>What "registers cleanly" does and does not promise.</b> It promises only that route
 * registration does not reject this declaration — nothing about whether a request against it succeeds.
 * Under the built-in {@code JsonRequestBodyDecoder} it will <em>not</em>: {@code decodeArray} calls
 * {@code TypeFactory.constructCollectionType(SortedSet.class, elementClass)} and Jackson's concrete
 * type for {@code SortedSet}/{@code NavigableSet} is {@code TreeSet}, so a non-{@link Comparable}
 * element yields a {@code ClassCastException} (a 500) at request time. That failure is deliberately
 * the decoder's to own: a custom {@code RequestBodyDecoder} may return a comparator-backed set, so the
 * route validator has no basis to adjudicate a body's element ordering. The fixture therefore exercises
 * registration only — it never sends a request.
 *
 * <p>The shape only occurs on the <em>generated</em> dispatch path: the reflective
 * {@code ResourceScanner} hard-codes {@code componentType = null} for BODY, while the codegen
 * resolver resolves the declared {@code List}/{@code Set} element type for BODY as well (a
 * deliberately deferred divergence), so the emitted {@code ParamMeta} carries a component type. The
 * companion {@link SortedSetBodyResource_JaxRsDescriptor} reproduces exactly that emitted shape.
 */
@Path("/sorted-body")
public class SortedSetBodyResource {

    /**
     * Body element type — deliberately NOT {@link Comparable}, which is the whole point of the fixture:
     * it is the element shape that the built-in JSON decoder cannot materialize into a
     * {@code SortedSet}. Registration must still admit it, because whether the shape works is the
     * decoder's contract, not the route validator's.
     */
    public static final class BodyElement {

        /** Public field so the type is a plain JSON-deserializable bean. */
        public String name;
    }

    /**
     * Accepts a JSON array body deserialized into a {@code SortedSet}. A body is deserialized by the
     * selected {@code RequestBodyDecoder}, never materialized element-wise as a {@code TreeSet} by
     * {@code ParameterExtractor.materializeCollection}, so the sorted-shape startup guard does not
     * apply.
     *
     * <p>This method is never invoked by the tests: with the built-in JSON decoder a request would fail
     * inside Jackson (see the class javadoc), and proving <em>registration</em> is the fixture's only
     * job.
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
