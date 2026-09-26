// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.legacy;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Legacy-shaped JAX-RS resource fixture unconditionally contributed by
 * {@link GeneratedJaxRsResourcesModule#extraResourceBinding}, in the shape today's
 * {@code GeneratedJaxRsResourcesModuleEmitter} emits for an unconditional DI-eligible resource.
 */
@Path("/extra")
public class ExtraResource {

    /** Construction count; reset before every test. */
    public static final AtomicInteger CONSTRUCTIONS = new AtomicInteger();

    /** Counts the construction. */
    @Inject
    public ExtraResource() {
        CONSTRUCTIONS.incrementAndGet();
    }

    /**
     * Handles {@code GET /extra}.
     *
     * @return the fixed body {@code "extra"}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    @Operation(operationId = "zeroDeclarationExtra")
    public String get() {
        return "extra";
    }
}
