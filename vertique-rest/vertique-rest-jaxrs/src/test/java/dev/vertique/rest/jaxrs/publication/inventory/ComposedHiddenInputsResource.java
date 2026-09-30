// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication.inventory;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Resource whose operations hide inputs through a composed annotation on the method: an annotation
 * type that itself carries a {@code @Parameter}, a {@code @Parameters} container, or an {@code
 * @Operation(parameters = ...)}, each naming an input by name and location. Swagger's reader takes
 * these entries from the annotation types of the method's annotations, one meta level deep. The
 * inputs themselves are unmarked, and every operation also binds an input no entry hides.
 *
 * <p>No generated descriptor companion exists for this class, and none may be added: it is a
 * reflection-path fixture, and it is not part of any parity mount.
 */
@Path("/composed-hidden")
public class ComposedHiddenInputsResource {

    /** Composed annotation carrying {@code @Parameter(name = "mx", in = HEADER, hidden = true)}. */
    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @Parameter(name = "mx", in = ParameterIn.HEADER, hidden = true)
    public @interface HiddenMxHeader {}

    /**
     * Composed annotation carrying a {@code @Parameters} container: {@code mq} (QUERY) hidden,
     * {@code mv} (QUERY) visible.
     */
    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @Parameters({
        @Parameter(name = "mq", in = ParameterIn.QUERY, hidden = true),
        @Parameter(name = "mv", in = ParameterIn.QUERY, hidden = false)
    })
    public @interface HiddenMqVisibleMv {}

    /**
     * Composed annotation carrying {@code @Operation(parameters = @Parameter(name = "mo", in =
     * QUERY, hidden = true))}.
     */
    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @Operation(parameters = @Parameter(name = "mo", in = ParameterIn.QUERY, hidden = true))
    public @interface HiddenMoByOperation {}

    /**
     * {@code GET /composed-hidden/header}: a composed {@code @Parameter} hides header {@code mx}.
     *
     * @param mx   header {@code mx}, hidden by the composed annotation (index 0)
     * @param page query {@code page}, not hidden (index 1)
     * @return a fixed body
     */
    @GET
    @Path("/header")
    @Produces(MediaType.TEXT_PLAIN)
    @HiddenMxHeader
    public String composedHeader(@HeaderParam("mx") String mx, @QueryParam("page") String page) {
        return "header";
    }

    /**
     * {@code GET /composed-hidden/container}: a composed {@code @Parameters} container hides
     * {@code mq} and declares {@code mv} visible.
     *
     * @param mq query {@code mq}, hidden by the composed container (index 0)
     * @param mv query {@code mv}, declared visible by the composed container (index 1)
     * @return a fixed body
     */
    @GET
    @Path("/container")
    @Produces(MediaType.TEXT_PLAIN)
    @HiddenMqVisibleMv
    public String composedContainer(@QueryParam("mq") String mq, @QueryParam("mv") String mv) {
        return "container";
    }

    /**
     * {@code GET /composed-hidden/operation}: a composed {@code @Operation(parameters = ...)} hides
     * {@code mo}.
     *
     * @param mo   query {@code mo}, hidden by the composed operation (index 0)
     * @param keep query {@code keep}, not hidden (index 1)
     * @return a fixed body
     */
    @GET
    @Path("/operation")
    @Produces(MediaType.TEXT_PLAIN)
    @HiddenMoByOperation
    public String composedOperation(@QueryParam("mo") String mo, @QueryParam("keep") String keep) {
        return "operation";
    }
}
