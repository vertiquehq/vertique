// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.manual;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * TP-005 (cases 15, 17 to 20) and TP-018's listed base resource. Carries no catalog entry: its only
 * possible membership path is a manual {@code @JaxRsResources} instance whose class satisfies
 * {@code sameSurface(MembershipBaseResource.class, instance.getClass())}. Never itself contributed
 * manually — every case contributes exactly one subclass instance, in its own module, so C-COMPOSE
 * step 6.6's "the only candidate" naming applies:
 *
 * <ul>
 *   <li>{@link MembershipAopProxyResource} — the AOP-proxy shape (TP-018, matches);
 *   <li>{@link MembershipOwnMethodResource} — adds a new annotated resource method (case 15);
 *   <li>{@link MembershipClassPathResource} — adds a class-level {@code @Path} (case 17);
 *   <li>{@link MembershipNewInterfaceResource} — implements a new {@code @GET}-declaring interface
 *       (case 18);
 *   <li>{@link MembershipGrandchildResource} — a grandchild, not a direct subclass (case 19);
 *   <li>{@link MembershipRolesAllowedResource} — an override carrying {@code @RolesAllowed} (case
 *       20).
 * </ul>
 */
@Path("/membership-base")
public class MembershipBaseResource {

    /** Public no-arg constructor, callable by every subclass's implicit {@code super()}. */
    public MembershipBaseResource() {}

    /**
     * Handles {@code GET /membership-base}. Every AOP-proxy-shaped subclass overrides this method,
     * keeping its name (PP2-004) and adding only the source-retained {@code @Override}.
     *
     * @return the fixed body {@code "membershipBase"}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String membershipBase() {
        return "membershipBase";
    }
}
