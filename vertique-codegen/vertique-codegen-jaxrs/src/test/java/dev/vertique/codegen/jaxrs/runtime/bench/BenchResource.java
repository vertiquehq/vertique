// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs.runtime.bench;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/**
 * Representative JAX-RS resource fixture used by {@link RegistrationBenchmark} and
 * {@link InvocationBenchmark}.
 *
 * <p>Contains 8 methods covering the common parameter shapes:
 * <ul>
 *   <li>Path, query, header scalar params</li>
 *   <li>Request body</li>
 *   <li>Bean-param composite</li>
 *   <li>Security annotation ({@code @RolesAllowed})</li>
 *   <li>Media-type constraints ({@code @Consumes}/{@code @Produces})</li>
 *   <li>Void return and {@code void} return</li>
 * </ul>
 *
 * <p>The companion class {@link BenchResource_JaxRsDescriptor} provides the generated-descriptor
 * fast path; classes without a companion exercise the reflective fallback path.
 */
@Path("/bench")
@RolesAllowed("user")
@Produces(MediaType.APPLICATION_JSON)
public class BenchResource {

    /** No-arg constructor required for reflective instantiation in benchmarks. */
    public BenchResource() {}

    /**
     * Lists resources filtered by a query param.
     *
     * @param page zero-based page index
     * @return a placeholder string
     */
    @GET
    @Operation(operationId = "bench-list")
    public String list(@QueryParam("page") int page) {
        return "list:" + page;
    }

    /**
     * Gets a single resource by id.
     *
     * @param id the resource id
     * @return a placeholder string
     */
    @GET
    @Path("/{id}")
    @Operation(operationId = "bench-get")
    public String get(@PathParam("id") String id) {
        return "get:" + id;
    }

    /**
     * Creates a new resource from the request body.
     *
     * @param body the request body
     * @return a placeholder string
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(operationId = "bench-create")
    public String create(String body) {
        return "create:" + body;
    }

    /**
     * Updates an existing resource.
     *
     * @param id   the resource id
     * @param body the request body
     * @return a placeholder string
     */
    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(operationId = "bench-update")
    public String update(@PathParam("id") String id, String body) {
        return "update:" + id;
    }

    /**
     * Deletes a resource by id — void return.
     *
     * @param id the resource id
     */
    @DELETE
    @Path("/{id}")
    @Operation(operationId = "bench-delete")
    public void delete(@PathParam("id") String id) {
        // noop in fixture
    }

    /**
     * Searches with multiple query params.
     *
     * @param q      the search query
     * @param limit  max result count
     * @param offset result start offset
     * @return a placeholder string
     */
    @GET
    @Path("/search")
    @Operation(operationId = "bench-search")
    public String search(@QueryParam("q") String q, @QueryParam("limit") int limit, @QueryParam("offset") int offset) {
        return "search:" + q;
    }

    /**
     * Processes a resource with a custom request header.
     *
     * @param id           the resource id
     * @param clientVersion the {@code X-Client-Version} header
     * @param body         the request body
     * @return a placeholder string
     */
    @POST
    @Path("/{id}/process")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(operationId = "bench-process")
    public String process(
            @PathParam("id") String id, @HeaderParam("X-Client-Version") String clientVersion, String body) {
        return "process:" + id;
    }

    /**
     * Queries using a bean-param composite.
     *
     * @param params the composite query params
     * @return a placeholder string
     */
    @GET
    @Path("/query")
    @Operation(operationId = "bench-query")
    public String query(@BeanParam BenchQueryParams params) {
        return "query:" + params;
    }
}
