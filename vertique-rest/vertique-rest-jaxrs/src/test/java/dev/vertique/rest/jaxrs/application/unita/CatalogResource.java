// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.unita;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Unconditional JAX-RS resource fixture, contributed by {@code unita}'s
 * {@link GeneratedJaxRsResourcesModule#catalogResourceBinding} and cataloged by
 * {@link GeneratedJaxRsResourcesModule#catalogResourceEntry}, in the generated-module shape that the other
 * hand-written modules copy exactly. Its resource method is named after this class:
 * deployed together with {@link ExtraResource} and {@link DisabledResource}, every method name in
 * this fixture set is distinct.
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
