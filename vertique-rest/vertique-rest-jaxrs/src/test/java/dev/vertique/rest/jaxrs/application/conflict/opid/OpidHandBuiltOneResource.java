// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.conflict.opid;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * TP-005 (T004) case (e) fixture: the sole resource of {@link OpidHandBuiltOneMountModule}'s
 * hand-built mount, built directly through {@code JaxRsRouterMount.Factory#create}. Its
 * {@link #list()} method's default operationId collides, cross-mount, with
 * {@link OpidHandBuiltTwoResource#list()}'s; with {@link OpidInactiveApplication}'s registration
 * declared (even though inactive), the cross-mount rule applies (AC-014.1).
 */
@Path("/opid-handbuilt-one")
public class OpidHandBuiltOneResource {

    /** Public no-arg constructor: constructed directly, never through Dagger. */
    public OpidHandBuiltOneResource() {}

    /**
     * Handles {@code GET /opid-handbuilt-one}, whose default operationId is {@code "list"}.
     *
     * @return the fixed body {@code "opid-handbuilt-one"}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String list() {
        return "opid-handbuilt-one";
    }
}
