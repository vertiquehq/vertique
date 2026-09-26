// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.policy;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * T005 TP-002 case (f) (G2-10): an entirely unannotated JAX-RS resource — like {@link
 * UnannotatedResource} — whose two operations' registration order ({@code RoutePathSpecificity},
 * most-specific-first) differs from their full-path lexical sort order, so the FR-013 warning's
 * {@code .sorted(...)} by full path is the only thing that can produce the expected message: {@link
 * #early()}'s one-segment path ({@code /aaa}) sorts lexically before {@link #late()}'s two-segment
 * path ({@code /zzz/yyy}), but {@code RoutePathSpecificity} registers the two-segment path FIRST
 * (more segments is more specific), the opposite order.
 */
@Path("/console")
public class OrderMismatchResource {

    /** Default constructor, injected as a lazy {@link jakarta.inject.Provider}. */
    @Inject
    public OrderMismatchResource() {}

    /**
     * Handles {@code GET .../console/aaa}: one path segment, lexically first, but registered
     * SECOND ({@code RoutePathSpecificity} ranks fewer segments as less specific).
     *
     * @return the fixed body {@code "early"}
     */
    @GET
    @Path("/aaa")
    @Produces(MediaType.TEXT_PLAIN)
    public String early() {
        return "early";
    }

    /**
     * Handles {@code GET .../console/zzz/yyy}: two path segments, lexically last, but registered
     * FIRST ({@code RoutePathSpecificity} ranks more segments as more specific).
     *
     * @return the fixed body {@code "late"}
     */
    @GET
    @Path("/zzz/yyy")
    @Produces(MediaType.TEXT_PLAIN)
    public String late() {
        return "late";
    }
}
