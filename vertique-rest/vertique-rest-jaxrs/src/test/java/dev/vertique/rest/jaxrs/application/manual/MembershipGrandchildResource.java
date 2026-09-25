// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.manual;

import jakarta.inject.Inject;

/**
 * TP-005 case 19's manually contributed instance: an AOP-proxy-shaped subclass of
 * {@link MembershipGrandchildLinkResource} — a <em>grandchild</em> of {@link MembershipBaseResource},
 * not a direct subclass. {@code sameSurface(MembershipBaseResource.class, this.getClass())} fails
 * because this class's direct superclass is {@link MembershipGrandchildLinkResource}, not
 * {@link MembershipBaseResource}, even though this class's own shape (final, no new annotations, no
 * new interface) is otherwise AOP-proxy-like.
 */
public final class MembershipGrandchildResource extends MembershipGrandchildLinkResource {

    /** Public {@code @Inject} constructor. */
    @Inject
    public MembershipGrandchildResource() {}

    /**
     * Overrides {@link MembershipGrandchildLinkResource#membershipBase()}, keeping the same method
     * name (PP2-004) and adding no annotation beyond the source-retained {@code @Override}.
     *
     * @return the base implementation's result
     */
    @Override
    public String membershipBase() {
        return super.membershipBase();
    }
}
