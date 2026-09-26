// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.manual;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JAX-RS resource fixture contributed manually via {@link ManualResourceModule} — the shape
 * sibling framework modules use: an {@code @Inject}-constructed instance bound one at a time
 * through {@code @Provides @IntoSet @JaxRsResources}, never through a generated module. C-COMPOSE
 * step 6's manual-match rule (and its {@code sameSurface} predicate) resolves this class as a
 * membership candidate against an application's {@code getClasses()} entries.
 */
@Path("/blob-like")
public class BlobLikeResource {

    /** Construction count; reset before every test via {@link #reset()}. */
    public static final AtomicInteger CONSTRUCTIONS = new AtomicInteger();

    /** Counts the construction. */
    @Inject
    public BlobLikeResource() {
        CONSTRUCTIONS.incrementAndGet();
    }

    /**
     * Handles {@code GET /blob-like}.
     *
     * @return the fixed body {@code "blobLike"}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String blobLike() {
        return "blobLike";
    }

    /** Resets {@link #CONSTRUCTIONS} to {@code 0}. */
    public static void reset() {
        CONSTRUCTIONS.set(0);
    }
}
