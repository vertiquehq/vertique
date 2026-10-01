// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.security.listing;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * The resource {@code /beta} of {@link RosterApi}: one operation, {@code GET} ({@value #ALPHA_LIST}),
 * requiring the scopeless scheme {@value RosterSchemeModule#SCHEME}. Its operation id sorts before
 * every other restricted operation of the application although its path sorts after theirs.
 */
@Path("/beta")
public class BetaResource {

    /** The operation id of {@link #alphaList}, which is its method name. */
    public static final String ALPHA_LIST = "alphaList";

    /** Public {@code @Inject} constructor. */
    @Inject
    public BetaResource() {}

    /**
     * Handles {@code GET /beta}.
     *
     * @return the fixed body {@code "list"}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    @SecurityRequirement(name = RosterSchemeModule.SCHEME)
    public String alphaList() {
        return "list";
    }
}
