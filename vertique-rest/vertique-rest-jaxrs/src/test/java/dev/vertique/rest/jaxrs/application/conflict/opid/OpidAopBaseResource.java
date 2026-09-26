// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.conflict.opid;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * TP-005 (T004) case (f) fixture: {@link OpidAopApplication} lists this class in
 * {@code getClasses()}, but its only bound {@code @JaxRsResources} instance is
 * {@link OpidAopProxyResource}, the AOP-proxy-shaped direct subclass that overrides
 * {@link #list()} keeping this class's resource surface ({@code sameSurface}). Beside it,
 * {@link OpidAopHandBuiltMountModule} hand-builds a mount holding an unproxied instance of this
 * exact class, passed directly to {@code JaxRsRouterMount.Factory#create}, never through
 * {@code @JaxRsResources}.
 */
@Path("/opid-aop-base")
public class OpidAopBaseResource {

    /** Public no-arg constructor, callable by {@link OpidAopProxyResource}'s implicit {@code super()}. */
    public OpidAopBaseResource() {}

    /**
     * Handles {@code GET .../opid-aop-base}, whose default operationId is {@code "list"}.
     *
     * @return the fixed body {@code "opid-aop-base"}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String list() {
        return "opid-aop-base";
    }
}
