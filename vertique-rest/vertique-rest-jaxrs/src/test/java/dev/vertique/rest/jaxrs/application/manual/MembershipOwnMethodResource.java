// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.manual;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * TP-005 case 15's direct subclass of {@link MembershipBaseResource}: declares its own new resource
 * method, carrying a runtime-retained JAX-RS annotation ({@code @GET}), so
 * {@code sameSurface(MembershipBaseResource.class, this.getClass())} fails ("no declared ... method
 * ... carries a runtime-retained annotation"). The new method is named after this subclass
 * (§ Scope and ownership).
 */
public class MembershipOwnMethodResource extends MembershipBaseResource {

    /** Public {@code @Inject} constructor. */
    @Inject
    public MembershipOwnMethodResource() {}

    /**
     * Handles {@code GET /membership-own-method}; the added resource method that trips
     * {@code sameSurface}.
     *
     * @return the fixed body {@code "membershipOwnMethod"}
     */
    @GET
    @Path("/membership-own-method")
    @Produces(MediaType.TEXT_PLAIN)
    public String membershipOwnMethod() {
        return "membershipOwnMethod";
    }
}
