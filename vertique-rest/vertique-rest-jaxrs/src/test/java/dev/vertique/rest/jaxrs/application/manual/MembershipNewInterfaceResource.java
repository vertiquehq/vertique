// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.manual;

import jakarta.inject.Inject;

/**
 * TP-005 case 18's direct subclass of {@link MembershipBaseResource}: implements
 * {@link MembershipGetInterface}, a new interface the base does not implement, so
 * {@code sameSurface(MembershipBaseResource.class, this.getClass())} fails.
 */
public class MembershipNewInterfaceResource extends MembershipBaseResource implements MembershipGetInterface {

    /** Public {@code @Inject} constructor. */
    @Inject
    public MembershipNewInterfaceResource() {}

    /**
     * Implements {@link MembershipGetInterface#membershipInterfaceMethod()}.
     *
     * @return the fixed body {@code "membershipInterfaceMethod"}
     */
    @Override
    public String membershipInterfaceMethod() {
        return "membershipInterfaceMethod";
    }
}
