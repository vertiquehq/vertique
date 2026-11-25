// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.unitb.membership;

import jakarta.ws.rs.core.Feature;
import jakarta.ws.rs.core.FeatureContext;

/**
 * Listed-only type: a class implementing {@link Feature}. Listed by
 * {@link MembershipViolationRegistrations#featureCaseRegistration} but never Dagger-bound;
 * the composer rejects it as an unsupported feature before any catalog or manual match is
 * attempted.
 */
public class SampleFeatureType implements Feature {

    /** Public no-arg constructor; never invoked by the composer. */
    public SampleFeatureType() {}

    /**
     * Never invoked by the composer.
     *
     * @param context the feature context
     * @return {@code true}
     */
    @Override
    public boolean configure(FeatureContext context) {
        return true;
    }
}
