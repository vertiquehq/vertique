// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.manual;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;

/**
 * TP-005 case 20's direct subclass of {@link MembershipBaseResource}: its override of
 * {@link MembershipBaseResource#membershipBase()} carries {@code @RolesAllowed}, a runtime-retained
 * annotation, so {@code sameSurface(MembershipBaseResource.class, this.getClass())} fails ("no
 * declared ... method ... carries a runtime-retained annotation").
 */
public class MembershipRolesAllowedResource extends MembershipBaseResource {

    /** Public {@code @Inject} constructor. */
    @Inject
    public MembershipRolesAllowedResource() {}

    /**
     * Overrides {@link MembershipBaseResource#membershipBase()}, keeping the same method name
     * (PP2-004) but adding {@code @RolesAllowed} — the annotation that trips {@code sameSurface}.
     *
     * @return the base implementation's result
     */
    @Override
    @RolesAllowed("admin")
    public String membershipBase() {
        return super.membershipBase();
    }
}
