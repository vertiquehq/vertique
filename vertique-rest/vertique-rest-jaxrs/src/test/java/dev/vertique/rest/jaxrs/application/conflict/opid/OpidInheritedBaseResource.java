// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.conflict.opid;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * TP-005 (T004) case (d) fixture: the base class {@link OpidInheritedFirstResource} and
 * {@link OpidInheritedSecondResource} both extend, inheriting {@link #list()} unchanged. Neither
 * subclass is ever itself instantiated as this exact type — each is a distinct, cataloged
 * resource class with its own {@code @Path} — so this class is never directly listed by an
 * application or contributed to a mount.
 */
@Path("/opid-inherited-base")
public class OpidInheritedBaseResource {

    /** Public no-arg constructor, callable by every subclass's implicit {@code super()}. */
    public OpidInheritedBaseResource() {}

    /**
     * Handles {@code GET .../opid-inherited-base}, whose default operationId is {@code "list"}.
     * Inherited unchanged by both {@link OpidInheritedFirstResource} and
     * {@link OpidInheritedSecondResource}.
     *
     * @return the fixed body {@code "opid-inherited-base"}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String list() {
        return "opid-inherited-base";
    }
}
