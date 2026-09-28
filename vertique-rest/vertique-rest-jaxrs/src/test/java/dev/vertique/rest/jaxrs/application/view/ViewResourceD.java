// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.view;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * TP-006's sole manual resource for {@link ViewAppD}. Never constructed: {@code d} is always
 * inactive in this proof, so it never mounts.
 */
@Path("/view/d")
public class ViewResourceD {

    /** Public {@code @Inject} constructor. */
    @Inject
    public ViewResourceD() {}

    /**
     * Handles {@code GET /view/d}.
     *
     * @return the fixed body {@code "d"}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String get() {
        return "d";
    }
}
