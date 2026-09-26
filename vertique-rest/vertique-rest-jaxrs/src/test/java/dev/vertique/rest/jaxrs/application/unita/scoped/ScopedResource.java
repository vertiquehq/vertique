// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.unita.scoped;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Single-proof (TP-003) {@code @Singleton}-scoped JAX-RS resource fixture, contributed by its own
 * C-GEN-shaped module ({@code unita.scoped}'s {@link GeneratedJaxRsResourcesModule}), kept out of
 * {@code unita}'s own module so that every other proof's expected resource set is unaffected. Its
 * scope proves that the composer resolves each selected catalog entry's {@code Provider} through
 * the surrounding Dagger component, so a {@code @Singleton}-scoped entry is constructed once across
 * repeated compositions built from the same component instance, while an unscoped sibling (
 * {@code unita}'s {@code CatalogResource}) is constructed once per composition.
 */
@Path("/scoped")
@Singleton
public class ScopedResource {

    /** Construction count; reset before every test via {@link #reset()}. */
    public static final AtomicInteger CONSTRUCTIONS = new AtomicInteger();

    /** Counts the construction. */
    @Inject
    public ScopedResource() {
        CONSTRUCTIONS.incrementAndGet();
    }

    /**
     * Handles {@code GET /scoped}.
     *
     * @return the fixed body {@code "scoped"}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String scoped() {
        return "scoped";
    }

    /** Resets {@link #CONSTRUCTIONS} to {@code 0}. */
    public static void reset() {
        CONSTRUCTIONS.set(0);
    }
}
