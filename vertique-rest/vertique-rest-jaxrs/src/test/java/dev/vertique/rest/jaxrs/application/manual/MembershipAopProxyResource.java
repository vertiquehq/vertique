// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.manual;

import jakarta.inject.Inject;

/**
 * TP-018's AOP-proxy-shaped direct subclass of {@link MembershipBaseResource}: {@code public
 * final}, extends the bean directly, adds no interface, and marks its override with only the
 * source-retained {@code @Override} — no other class or method annotation, no parameter
 * annotations (matching {@code AopProxyEmitter.java:124-127,449-460}). {@code sameSurface} accepts
 * exactly this shape, so a {@link MembershipBaseResource}-listing application successfully selects
 * this instance.
 */
public final class MembershipAopProxyResource extends MembershipBaseResource {

    /** Public {@code @Inject} constructor. */
    @Inject
    public MembershipAopProxyResource() {}

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
