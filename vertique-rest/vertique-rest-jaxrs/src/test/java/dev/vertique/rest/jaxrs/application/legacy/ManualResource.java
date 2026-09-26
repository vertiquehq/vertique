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
 * JAX-RS resource fixture contributed manually via {@link ManualResourceModule} — the shape
 * sibling framework modules use: an {@code @Inject}-constructed instance bound one at a time
 * through {@code @Provides @IntoSet @JaxRsResources}, never through a generated module.
 */
@Path("/manual")
public class ManualResource {

    /** Construction count; reset before every test. */
    public static final AtomicInteger CONSTRUCTIONS = new AtomicInteger();

    /** Counts the construction. */
    @Inject
    public ManualResource() {
        CONSTRUCTIONS.incrementAndGet();
    }

    /**
     * Handles {@code GET /manual}.
     *
     * @return the fixed body {@code "manual"}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    @Operation(operationId = "zeroDeclarationManual")
    public String get() {
        return "manual";
    }
}
