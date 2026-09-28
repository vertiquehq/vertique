// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.view;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/** TP-006's sole manual resource for {@link ViewAppC}. */
@Path("/view/c")
public class ViewResourceC {

    /** Public {@code @Inject} constructor. */
    @Inject
    public ViewResourceC() {}

    /**
     * Handles {@code GET /view/c}.
     *
     * @return the fixed body {@code "c"}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String get() {
        return "c";
    }
}
