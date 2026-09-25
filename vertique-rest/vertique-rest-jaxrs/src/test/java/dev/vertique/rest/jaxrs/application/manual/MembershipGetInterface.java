// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.manual;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * TP-005 case 18's new interface, declaring a {@code @GET} method. Implemented by
 * {@link MembershipNewInterfaceResource}, whose superclass ({@link MembershipBaseResource})
 * implements no interface, so {@code sameSurface} fails ("actual adds no interface beyond those
 * declared implements").
 */
public interface MembershipGetInterface {

    /**
     * Handles {@code GET /membership-interface}.
     *
     * @return the fixed body {@code "membershipInterfaceMethod"}
     */
    @GET
    @Path("/membership-interface")
    @Produces(MediaType.TEXT_PLAIN)
    String membershipInterfaceMethod();
}
