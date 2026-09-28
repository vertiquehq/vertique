// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.view;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/** TP-006's sole manual resource for {@link ViewAppA}. */
@Path("/view/a")
public class ViewResourceA {

    /** Public {@code @Inject} constructor. */
    @Inject
    public ViewResourceA() {}

    /**
     * Handles {@code GET /view/a}.
     *
     * @return the fixed body {@code "a"}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String get() {
        return "a";
    }
}
