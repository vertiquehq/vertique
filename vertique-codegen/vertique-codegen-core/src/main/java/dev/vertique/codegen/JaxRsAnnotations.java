// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen;

import java.util.List;

/**
 * INTERNAL framework seam — processor-authoring substrate consumed by sibling framework modules;
 * not an application contract and outside the maturity promise. An application uses the wiring
 * annotations this module documents and never calls this type.
 *
 * <p>Fully-qualified names of the JAX-RS, Jakarta security, and framework annotations recognised by
 * the codegen modules. Centralised here so that both rest-client codegen and jaxrs APT validators
 * reference the same constants — no string literals scattered across the validator code.
 */
public final class JaxRsAnnotations {

    private JaxRsAnnotations() {}

    // --- JAX-RS structural ---

    /** FQN of {@code jakarta.ws.rs.Path}. */
    public static final String PATH = "jakarta.ws.rs.Path";

    /**
     * FQN of {@code jakarta.ws.rs.core.Context}.
     *
     * <p>A parameter annotated with {@code @Context} requests framework injection of a context
     * object (e.g. {@code RoutingContext}, {@code SecurityContext}, or any
     * {@code ContextValue} subtype). The compile-time classifier treats {@code @Context}
     * presence as equivalent to matching a known injectable type.
     */
    public static final String CONTEXT = "jakarta.ws.rs.core.Context";

    /** FQN of {@code jakarta.ws.rs.PathParam}. */
    public static final String PATH_PARAM = "jakarta.ws.rs.PathParam";

    /** FQN of {@code jakarta.ws.rs.QueryParam}. */
    public static final String QUERY_PARAM = "jakarta.ws.rs.QueryParam";

    /** FQN of {@code jakarta.ws.rs.HeaderParam}. */
    public static final String HEADER_PARAM = "jakarta.ws.rs.HeaderParam";

    /** FQN of {@code jakarta.ws.rs.CookieParam}. */
    public static final String COOKIE_PARAM = "jakarta.ws.rs.CookieParam";

    /** FQN of {@code jakarta.ws.rs.FormParam}. */
    public static final String FORM_PARAM = "jakarta.ws.rs.FormParam";

    /** FQN of {@code jakarta.ws.rs.BeanParam}. */
    public static final String BEAN_PARAM = "jakarta.ws.rs.BeanParam";

    // --- JAX-RS HTTP verbs ---

    /** FQN of {@code jakarta.ws.rs.GET}. */
    public static final String GET = "jakarta.ws.rs.GET";

    /** FQN of {@code jakarta.ws.rs.POST}. */
    public static final String POST = "jakarta.ws.rs.POST";

    /** FQN of {@code jakarta.ws.rs.PUT}. */
    public static final String PUT = "jakarta.ws.rs.PUT";

    /** FQN of {@code jakarta.ws.rs.DELETE}. */
    public static final String DELETE = "jakarta.ws.rs.DELETE";

    /** FQN of {@code jakarta.ws.rs.PATCH}. */
    public static final String PATCH = "jakarta.ws.rs.PATCH";

    /** FQN of {@code jakarta.ws.rs.HEAD}. */
    public static final String HEAD = "jakarta.ws.rs.HEAD";

    /** FQN of {@code jakarta.ws.rs.OPTIONS}. */
    public static final String OPTIONS = "jakarta.ws.rs.OPTIONS";

    /** Ordered list of the seven JAX-RS HTTP verb annotation FQNs. */
    public static final List<String> HTTP_VERBS = List.of(GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS);

    // --- Jakarta security ---

    /** FQN of {@code jakarta.annotation.security.DenyAll}. */
    public static final String DENY_ALL = "jakarta.annotation.security.DenyAll";

    /** FQN of {@code jakarta.annotation.security.PermitAll}. */
    public static final String PERMIT_ALL = "jakarta.annotation.security.PermitAll";

    /** FQN of {@code jakarta.annotation.security.RolesAllowed}. */
    public static final String ROLES_ALLOWED = "jakarta.annotation.security.RolesAllowed";

    // --- Framework ---

    /** FQN of {@code dev.vertique.rest.core.security.Authorized}. */
    public static final String AUTHORIZED = "dev.vertique.rest.core.security.Authorized";

    /** FQN of {@code dev.vertique.security.authz.RequiresAction}. */
    public static final String REQUIRES_ACTION = "dev.vertique.security.authz.RequiresAction";

    /** FQN of {@code dev.vertique.rest.core.request.RequestParams}. */
    public static final String REQUEST_PARAMS = "dev.vertique.rest.core.request.RequestParams";
}
