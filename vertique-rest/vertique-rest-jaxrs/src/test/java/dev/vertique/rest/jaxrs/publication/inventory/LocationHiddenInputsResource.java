// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication.inventory;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/**
 * Resource whose operations hide inputs from the method by entries that name a location only
 * implicitly or name a cookie or path location: an entry without a location hides a query input, a
 * query and a header input sharing one name, and a form input; a cookie entry and a path entry hide
 * only the input at their location, not a query input of the same name; and a visible entry names an
 * input already hidden by its own marker. Every operation also binds an input no entry hides.
 *
 * <p>No generated descriptor companion exists for this class, and none may be added: it is a
 * reflection-path fixture, and it is not part of any parity mount.
 */
@Path("/location-hidden")
public class LocationHiddenInputsResource {

    /**
     * {@code GET /location-hidden/default}: an entry without a location hides query {@code dq}.
     *
     * @param dq   query {@code dq}, hidden by the method (index 0)
     * @param keep query {@code keep}, not hidden (index 1)
     * @return a fixed body
     */
    @GET
    @Path("/default")
    @Produces(MediaType.TEXT_PLAIN)
    @Parameter(name = "dq", hidden = true)
    public String hideDefaultQuery(@QueryParam("dq") String dq, @QueryParam("keep") String keep) {
        return "default";
    }

    /**
     * {@code GET /location-hidden/shared}: one entry without a location hides both inputs named
     * {@code dup}.
     *
     * @param dupQuery  query {@code dup}, hidden by the method (index 0)
     * @param dupHeader header {@code dup}, hidden by the same entry (index 1)
     * @param other     query {@code other}, not hidden (index 2)
     * @return a fixed body
     */
    @GET
    @Path("/shared")
    @Produces(MediaType.TEXT_PLAIN)
    @Parameter(name = "dup", hidden = true)
    public String hideSharedName(
            @QueryParam("dup") String dupQuery,
            @HeaderParam("dup") String dupHeader,
            @QueryParam("other") String other) {
        return "shared";
    }

    /**
     * {@code POST /location-hidden/form}: an entry without a location hides form {@code fq}.
     *
     * @param fq form {@code fq}, hidden by the method (index 0)
     * @param fk form {@code fk}, not hidden (index 1)
     * @return a fixed body
     */
    @POST
    @Path("/form")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_PLAIN)
    @Parameter(name = "fq", hidden = true)
    public String hideForm(@FormParam("fq") String fq, @FormParam("fk") String fk) {
        return "form";
    }

    /**
     * {@code GET /location-hidden/cookie}: a cookie entry hides cookie {@code ck} only.
     *
     * @param cookie cookie {@code ck}, hidden by the method (index 0)
     * @param query  query {@code ck}, not hidden (index 1)
     * @return a fixed body
     */
    @GET
    @Path("/cookie")
    @Produces(MediaType.TEXT_PLAIN)
    @Parameter(name = "ck", in = ParameterIn.COOKIE, hidden = true)
    public String hideCookie(@CookieParam("ck") String cookie, @QueryParam("ck") String query) {
        return "cookie";
    }

    /**
     * {@code GET /location-hidden/path/{pid}}: a path entry hides path {@code pid} only.
     *
     * @param path  path {@code pid}, hidden by the method (index 0)
     * @param query query {@code pid}, not hidden (index 1)
     * @return a fixed body
     */
    @GET
    @Path("/path/{pid}")
    @Produces(MediaType.TEXT_PLAIN)
    @Parameter(name = "pid", in = ParameterIn.PATH, hidden = true)
    public String hidePath(@PathParam("pid") String path, @QueryParam("pid") String query) {
        return "path";
    }

    /**
     * {@code GET /location-hidden/own}: a visible entry names query {@code own}, which its own
     * {@code @Parameter(hidden = true)} hides.
     *
     * @param own  query {@code own}, hidden by its own marker (index 0)
     * @param seen query {@code seen}, not hidden (index 1)
     * @return a fixed body
     */
    @GET
    @Path("/own")
    @Produces(MediaType.TEXT_PLAIN)
    @Parameter(name = "own", in = ParameterIn.QUERY, hidden = false)
    public String keepOwnHidden(
            @QueryParam("own") @Parameter(hidden = true) String own, @QueryParam("seen") String seen) {
        return "own";
    }
}
