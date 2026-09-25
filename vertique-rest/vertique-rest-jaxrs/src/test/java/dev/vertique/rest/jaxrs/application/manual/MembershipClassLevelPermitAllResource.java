// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.manual;

import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;

/**
 * TP-005 case 23's (G-03) direct subclass of {@link MembershipBaseResource}: carries a class-level
 * {@code @PermitAll} and nothing else — no method override, no new method, no new interface — so
 * {@code sameSurface(MembershipBaseResource.class, this.getClass())} fails on its first check
 * ("actual declares no runtime-retained annotation"), proving the class-level annotation rule has a
 * test that can fail (M-2/G-03).
 */
@PermitAll
public class MembershipClassLevelPermitAllResource extends MembershipBaseResource {

    /** Public {@code @Inject} constructor. */
    @Inject
    public MembershipClassLevelPermitAllResource() {}
}
