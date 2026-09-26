// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.unita.membership;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TP-005 case 6's resource: cataloged by {@link AmbiguousResourceCatalogModule} <em>and</em>
 * contributed manually by {@code manual.membership.AmbiguousResourceManualModule}, so it has both a
 * catalog match and a manual match when listed. C-COMPOSE step 6.7 rejects it as ambiguous.
 */
@Path("/ambiguous")
public class AmbiguousResource {

    /** Construction count; reset before every case via {@link #reset()}. */
    public static final AtomicInteger CONSTRUCTIONS = new AtomicInteger();

    /** Counts the construction. */
    @Inject
    public AmbiguousResource() {
        CONSTRUCTIONS.incrementAndGet();
    }

    /**
     * Handles {@code GET /ambiguous}.
     *
     * @return the fixed body {@code "ambiguous"}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String ambiguous() {
        return "ambiguous";
    }

    /** Resets {@link #CONSTRUCTIONS} to {@code 0}. */
    public static void reset() {
        CONSTRUCTIONS.set(0);
    }
}
