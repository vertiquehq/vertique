// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.it.apps.resources;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * DI-eligible JAX-RS resource fixture selected by {@code PublicApplication} (T003 TP-005). The real
 * {@code JaxRsPipelineProcessor} contributes this class to the generated resource catalog because its
 * constructor carries {@code @Inject}.
 */
@Path("/catalog")
public class CatalogResource {

    /** Construction count; reset before every test via {@link #reset()}. */
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
    public String catalog() {
        return "catalog";
    }

    /** Resets {@link #CONSTRUCTIONS} to {@code 0}. */
    public static void reset() {
        CONSTRUCTIONS.set(0);
    }
}
