// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication.inventory;

import dev.vertique.core.validation.ValidateWith;
import io.swagger.v3.oas.annotations.Parameter;
import io.vertx.ext.web.RoutingContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.groups.ConvertGroup;
import jakarta.validation.groups.Default;
import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;

/**
 * Generated-path twin of {@link OrdersResource}: identical methods, parameters, and annotations,
 * except that its composites are the generated-path twins {@link GeneratedFilters},
 * {@link GeneratedSearchParams}, and {@link GeneratedConversions}.
 *
 * <p>Its hand-written companion {@link GeneratedOrdersResource_JaxRsDescriptor} makes the scanner
 * take the generated-descriptor path. Keep every declaration in step with {@link OrdersResource}
 * and with the companion.
 */
@Path("/")
public class GeneratedOrdersResource {

    /**
     * {@code POST /orders/{id}}; see {@link OrdersResource#createOrder}.
     *
     * @param id      path {@code id} (index 0)
     * @param q       query {@code q}, defaulted (index 1)
     * @param trace   header {@code X-Trace} (index 2)
     * @param session cookie {@code session} (index 3)
     * @param ctx     the routing context, not a request input (index 4)
     * @param filters {@code @Valid} bean-param composite (index 5)
     * @param order   the request body (index 6)
     * @param search  {@code @RequestParams} composite without {@code @Valid} (index 7)
     * @param region  query {@code region}, {@code @NotNull} in {@code Default} (index 8)
     * @param audit   query {@code audit}, {@code @NotNull} in {@link Audit} only (index 9)
     * @return a fixed body
     */
    @POST
    @Path("/orders/{id}")
    @Produces(MediaType.TEXT_PLAIN)
    public String createOrder(
            @PathParam("id") @Parameter(description = "order id") String id,
            @QueryParam("q") @DefaultValue("10") int q,
            @HeaderParam("X-Trace") String trace,
            @CookieParam("session") String session,
            @Context RoutingContext ctx,
            @BeanParam @Valid GeneratedFilters filters,
            Order order,
            GeneratedSearchParams search,
            @QueryParam("region") @NotNull String region,
            @QueryParam("audit") @NotNull(groups = Audit.class) String audit) {
        return "created";
    }

    /**
     * {@code POST /notes}; see {@link OrdersResource#addNote}.
     *
     * @param note form {@code note}
     * @return a fixed body
     */
    @POST
    @Path("/notes")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_PLAIN)
    public String addNote(@FormParam("note") String note) {
        return "noted";
    }

    /**
     * {@code PUT /audits/{id}}, validated with {@link StrictAudit}; see
     * {@link OrdersResource#updateAudit}.
     *
     * @param id     path {@code id}
     * @param audit  query {@code audit}, {@code @NotNull} in {@link Audit} only
     * @param region query {@code region}, {@code @NotNull} in {@code Default}
     * @return a fixed body
     */
    @PUT
    @Path("/audits/{id}")
    @Produces(MediaType.TEXT_PLAIN)
    @ValidateWith(StrictAudit.class)
    public String updateAudit(
            @PathParam("id") String id,
            @QueryParam("audit") @NotNull(groups = Audit.class) String audit,
            @QueryParam("region") @NotNull String region) {
        return "audited";
    }

    /**
     * {@code GET /conversions}; see {@link OrdersResource#listConversions}.
     *
     * @param conversions {@code @Valid @ConvertGroup} bean-param composite
     * @param channel     query {@code channel}, {@code @NotNull} in {@code Default} and in {@link Audit}
     * @return a fixed body
     */
    @GET
    @Path("/conversions")
    @Produces(MediaType.TEXT_PLAIN)
    public String listConversions(
            @BeanParam @Valid @ConvertGroup(from = Default.class, to = Audit.class) GeneratedConversions conversions,
            @QueryParam("channel") @NotNull @NotNull(groups = Audit.class) String channel) {
        return "converted";
    }
}
