// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.manual;

import jakarta.inject.Inject;
import jakarta.ws.rs.QueryParam;

/**
 * TP-005 case 24's (G-03) direct subclass of {@link MembershipBaseResource}: overrides
 * {@link MembershipBaseResource#membershipBaseFiltered(String)}, keeping the same method name
 * (PP2-004) and adding no method-level annotation, but annotating the override's own parameter with
 * {@code @QueryParam}, a runtime-retained annotation the base's declaration does not carry on its
 * parameter. {@code sameSurface(MembershipBaseResource.class, this.getClass())} fails on the
 * parameter-annotation check ("no declared ... method ... carries a runtime-retained annotation, on
 * the method or on any of its parameters"), proving the parameter-annotation rule has a test that
 * can fail (M-2/G-03).
 */
public class MembershipParamAnnotationOnlyResource extends MembershipBaseResource {

    /** Public {@code @Inject} constructor. */
    @Inject
    public MembershipParamAnnotationOnlyResource() {}

    /**
     * Overrides {@link MembershipBaseResource#membershipBaseFiltered(String)}, keeping the same
     * method name (PP2-004) and adding no method-level annotation, but annotating the override's
     * own parameter with {@code @QueryParam} — the annotation that trips {@code sameSurface}.
     *
     * @param filter the query parameter this override annotates, unlike the base declaration
     * @return the base implementation's result
     */
    @Override
    public String membershipBaseFiltered(@QueryParam("filter") String filter) {
        return super.membershipBaseFiltered(filter);
    }
}
