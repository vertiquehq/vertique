// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.security.listing;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * The resource {@code /alpha} of {@link RosterApi}: two operations on one path, {@code POST}
 * ({@value #ZULU_CREATE}, declared first) and {@code GET} ({@value #YANKEE_READ}), each requiring the
 * scopeless scheme {@value RosterSchemeModule#SCHEME}.
 */
@Path("/alpha")
@SecurityRequirement(name = RosterSchemeModule.SCHEME)
public class AlphaResource {

    /** The operation id of {@link #zuluCreate}, which is its method name. */
    public static final String ZULU_CREATE = "zuluCreate";

    /** The operation id of {@link #yankeeRead}, which is its method name. */
    public static final String YANKEE_READ = "yankeeRead";

    /** Public {@code @Inject} constructor. */
    @Inject
    public AlphaResource() {}

    /**
     * Handles {@code POST /alpha}.
     *
     * @return the fixed body {@code "created"}
     */
    @POST
    @Produces(MediaType.TEXT_PLAIN)
    public String zuluCreate() {
        return "created";
    }

    /**
     * Handles {@code GET /alpha}.
     *
     * @return the fixed body {@code "read"}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String yankeeRead() {
        return "read";
    }
}
