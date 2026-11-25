// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.manual;

import jakarta.inject.Inject;
import jakarta.ws.rs.Path;

/**
 * The direct subclass of {@link MembershipBaseResource}: adds a class-level
 * {@code @Path}, a runtime-retained annotation the base does not declare, so
 * {@code sameSurface(MembershipBaseResource.class, this.getClass())} fails ("actual declares no
 * runtime-retained annotation").
 */
@Path("/membership-class-path")
public class MembershipClassPathResource extends MembershipBaseResource {

    /** Public {@code @Inject} constructor. */
    @Inject
    public MembershipClassPathResource() {}
}
