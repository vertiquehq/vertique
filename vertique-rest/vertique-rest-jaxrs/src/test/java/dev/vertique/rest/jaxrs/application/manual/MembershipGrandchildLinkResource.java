// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.manual;

/**
 * TP-005 case 19's structural link: an AOP-proxy-shaped (but non-final, so it can itself be
 * extended) direct subclass of {@link MembershipBaseResource}. Purely a compile-time superclass for
 * {@link MembershipGrandchildResource}; never itself Dagger-bound or contributed to any
 * {@code @JaxRsResources} multibinding, so it plays no role in membership matching on its own.
 */
public class MembershipGrandchildLinkResource extends MembershipBaseResource {

    /** Public no-arg constructor, callable by {@link MembershipGrandchildResource}'s implicit {@code super()}. */
    public MembershipGrandchildLinkResource() {}

    /**
     * Overrides {@link MembershipBaseResource#membershipBase()}, keeping the same method name
     * (PP2-004) and adding no annotation beyond the source-retained {@code @Override}.
     *
     * @return the base implementation's result
     */
    @Override
    public String membershipBase() {
        return super.membershipBase();
    }
}
