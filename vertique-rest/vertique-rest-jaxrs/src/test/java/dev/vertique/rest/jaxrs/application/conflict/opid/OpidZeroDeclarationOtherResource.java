// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.conflict.opid;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * TP-005 (T004) case (c) fixture: the sole resource of {@link OpidZeroDeclarationResourcesModule}'s
 * hand-built {@code /other/*} mount, built directly through {@code JaxRsRouterMount.Factory#create}
 * — never contributed to {@code @JaxRsResources}. Its {@link #list()} method's default operationId
 * collides, cross-mount, with {@link OpidZeroDeclarationManualResource#list()}'s, but with no
 * registration declared in this case, only the existing per-mount rule applies.
 */
@Path("/opid-zero-other")
public class OpidZeroDeclarationOtherResource {

    /** Public no-arg constructor: constructed directly, never through Dagger. */
    public OpidZeroDeclarationOtherResource() {}

    /**
     * Handles {@code GET /opid-zero-other}, whose default operationId is {@code "list"}.
     *
     * @return the fixed body {@code "opid-zero-other"}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String list() {
        return "opid-zero-other";
    }
}
