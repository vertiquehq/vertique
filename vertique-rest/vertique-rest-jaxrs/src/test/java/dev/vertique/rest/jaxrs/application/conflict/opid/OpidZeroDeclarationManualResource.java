// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.conflict.opid;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * TP-005 (T004) case (c) fixture: contributed manually via {@link OpidZeroDeclarationResourcesModule}
 * into {@code @JaxRsResources}, so it becomes the default mount's sole resource at
 * {@code jaxrs.basePath} ({@code /api/*}) in this case's zero-declaration composition (no
 * {@code GeneratedJaxRsApplicationRegistration} module is included at all). Its {@link #list()}
 * method's default operationId ({@code "list"}) collides, cross-mount, with
 * {@link OpidZeroDeclarationOtherResource#list()}'s, but with no registration declared, only the
 * existing per-mount rule applies — the collision must not fail deployment.
 */
@Path("/opid-zero-manual")
public class OpidZeroDeclarationManualResource {

    /** Public {@code @Inject} constructor. */
    @Inject
    public OpidZeroDeclarationManualResource() {}

    /**
     * Handles {@code GET /opid-zero-manual}, whose default operationId is {@code "list"}.
     *
     * @return the fixed body {@code "opid-zero-manual"}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String list() {
        return "opid-zero-manual";
    }
}
