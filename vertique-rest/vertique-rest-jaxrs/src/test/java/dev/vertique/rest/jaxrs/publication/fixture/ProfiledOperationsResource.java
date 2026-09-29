// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication.fixture;

import dev.vertique.core.json.JsonProfile;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * T006 TP-003's fixture resource: two path-parameterized reads, one resolving the configured
 * default request-body JSON profile and the other overriding it with a method-level
 * {@link JsonProfile}.
 */
@Path("/profiled/{id}")
public class ProfiledOperationsResource {

    /** This resource's method-level {@link JsonProfile} value. */
    public static final String METHOD_PROFILE_ID = "method-profile";

    /**
     * Resolves the configured default profile (no method-level {@link JsonProfile}).
     *
     * @param id the path id
     * @return the id, echoed
     */
    @GET
    @Path("/default")
    @Produces(MediaType.TEXT_PLAIN)
    public String byDefaultProfile(@PathParam("id") String id) {
        return "default:" + id;
    }

    /**
     * Resolves {@link #METHOD_PROFILE_ID} via a method-level {@link JsonProfile}.
     *
     * @param id the path id
     * @return the id, echoed
     */
    @GET
    @Path("/method")
    @Produces(MediaType.TEXT_PLAIN)
    @JsonProfile(METHOD_PROFILE_ID)
    public String byMethodProfile(@PathParam("id") String id) {
        return "method:" + id;
    }
}
