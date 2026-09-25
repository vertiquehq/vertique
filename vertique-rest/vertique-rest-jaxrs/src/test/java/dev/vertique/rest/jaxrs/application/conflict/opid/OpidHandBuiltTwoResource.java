// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.conflict.opid;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * TP-005 (T004) case (e) fixture: the sole resource of {@link OpidHandBuiltTwoMountModule}'s
 * hand-built mount, built directly through {@code JaxRsRouterMount.Factory#create}. Its
 * {@link #list()} method's default operationId collides, cross-mount, with
 * {@link OpidHandBuiltOneResource#list()}'s.
 */
@Path("/opid-handbuilt-two")
public class OpidHandBuiltTwoResource {

    /** Public no-arg constructor: constructed directly, never through Dagger. */
    public OpidHandBuiltTwoResource() {}

    /**
     * Handles {@code GET /opid-handbuilt-two}, whose default operationId is {@code "list"}.
     *
     * @return the fixed body {@code "opid-handbuilt-two"}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String list() {
        return "opid-handbuilt-two";
    }
}
