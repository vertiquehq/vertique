// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.security.orders;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * The resource {@value #ROUTE} of {@link OrdersApi}. Each operation is named for the security shape
 * it declares, and each answers {@code GET} with a fixed plain-text body:
 *
 * <ul>
 *   <li>{@code GET /orders} ({@value #LIST_ORDERS}): one scopeless requirement for {@value
 *       OrderSchemeHandlers#BEARER_AUTH};
 *   <li>{@code GET /orders/read} ({@value #READ_ORDER}): one requirement for {@value
 *       OrderSchemeHandlers#BEARER_AUTH} with the scope {@value #READ_SCOPE};
 *   <li>{@code GET /orders/search} ({@value #SEARCH_ORDERS}): two scopeless alternatives, {@value
 *       OrderSchemeHandlers#BEARER_AUTH} then {@value OrderSchemeHandlers#API_KEY_AUTH};
 *   <li>{@code GET /orders/admin} ({@value #ADMIN_ORDERS}): one scopeless requirement for {@value
 *       OrderSchemeHandlers#BEARER_AUTH} and a required role;
 *   <li>{@code GET /orders/ping} ({@value #PING}): open to every caller, with no requirement;
 *   <li>{@code GET /orders/internal} ({@value #INTERNAL_ORDERS}): hidden from the document, with
 *       one requirement for the scheme only it references, whose handler describes nothing.
 * </ul>
 */
@Path(OrderResource.ROUTE)
public class OrderResource {

    /** The resource path, relative to the mount. */
    public static final String ROUTE = "/orders";

    /** The path of {@link #readOrder}, relative to the resource. */
    public static final String READ_PATH = "/read";

    /** The path of {@link #searchOrders}, relative to the resource. */
    public static final String SEARCH_PATH = "/search";

    /** The path of {@link #adminOrders}, relative to the resource. */
    public static final String ADMIN_PATH = "/admin";

    /** The path of {@link #ping}, relative to the resource. */
    public static final String PING_PATH = "/ping";

    /** The path of {@link #internalOrders}, relative to the resource. */
    public static final String INTERNAL_PATH = "/internal";

    /** The operation id of {@link #listOrders}. */
    public static final String LIST_ORDERS = "listOrders";

    /** The operation id of {@link #readOrder}. */
    public static final String READ_ORDER = "readOrder";

    /** The operation id of {@link #searchOrders}. */
    public static final String SEARCH_ORDERS = "searchOrders";

    /** The operation id of {@link #adminOrders}. */
    public static final String ADMIN_ORDERS = "adminOrders";

    /** The operation id of {@link #ping}. */
    public static final String PING = "ping";

    /** The operation id of {@link #internalOrders}. */
    public static final String INTERNAL_ORDERS = "internalOrders";

    /** The scope {@link #readOrder} requires. */
    public static final String READ_SCOPE = "orders.read";

    /** The role {@link #adminOrders} requires; no document may name it. */
    public static final String ADMIN_ROLE = "order-admin";

    /** Creates the resource. */
    public OrderResource() {}

    /**
     * Handles {@code GET /orders}.
     *
     * @return a fixed body
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    @SecurityRequirement(name = OrderSchemeHandlers.BEARER_AUTH)
    public String listOrders() {
        return "orders";
    }

    /**
     * Handles {@code GET /orders/read}.
     *
     * @return a fixed body
     */
    @GET
    @Path(READ_PATH)
    @Produces(MediaType.TEXT_PLAIN)
    @SecurityRequirement(name = OrderSchemeHandlers.BEARER_AUTH, scopes = READ_SCOPE)
    public String readOrder() {
        return "order";
    }

    /**
     * Handles {@code GET /orders/search}.
     *
     * @return a fixed body
     */
    @GET
    @Path(SEARCH_PATH)
    @Produces(MediaType.TEXT_PLAIN)
    @SecurityRequirement(name = OrderSchemeHandlers.BEARER_AUTH)
    @SecurityRequirement(name = OrderSchemeHandlers.API_KEY_AUTH)
    public String searchOrders() {
        return "found";
    }

    /**
     * Handles {@code GET /orders/admin}.
     *
     * @return a fixed body
     */
    @GET
    @Path(ADMIN_PATH)
    @Produces(MediaType.TEXT_PLAIN)
    @SecurityRequirement(name = OrderSchemeHandlers.BEARER_AUTH)
    @RolesAllowed(ADMIN_ROLE)
    public String adminOrders() {
        return "admin";
    }

    /**
     * Handles {@code GET /orders/ping}.
     *
     * @return a fixed body
     */
    @GET
    @Path(PING_PATH)
    @Produces(MediaType.TEXT_PLAIN)
    @PermitAll
    public String ping() {
        return "pong";
    }

    /**
     * Handles {@code GET /orders/internal}, hidden from the document.
     *
     * @return a fixed body
     */
    @GET
    @Path(INTERNAL_PATH)
    @Produces(MediaType.TEXT_PLAIN)
    @Hidden
    @SecurityRequirement(name = OrderSchemeHandlers.HIDDEN_ONLY)
    public String internalOrders() {
        return "internal";
    }
}
