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
 * {@link GeneratedJaxRsResourcesModule#catalogResourceBinding}, in the shape today's
 * {@code GeneratedJaxRsResourcesModuleEmitter} emits for an unconditional DI-eligible resource. Its
 * {@code GET} is the route {@code ZeroApplicationCompositionCharacterizationIT} exercises over HTTP.
 */
@Path("/catalog")
public class CatalogResource {

    /** Construction count; reset before every test. */
    public static final AtomicInteger CONSTRUCTIONS = new AtomicInteger();

    /** Counts the construction. */
    @Inject
    public CatalogResource() {
        CONSTRUCTIONS.incrementAndGet();
    }

    /**
     * Handles {@code GET /catalog}.
     *
     * @return the fixed body {@code "catalog"}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    @Operation(operationId = "zeroDeclarationCatalog")
    public String get() {
        return "catalog";
    }
}
