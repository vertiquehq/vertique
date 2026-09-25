// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.unitb.membership;

import jakarta.ws.rs.core.Feature;
import jakarta.ws.rs.core.FeatureContext;

/**
 * TP-005 case 3's listed-only type: a class implementing {@link Feature}. Listed in
 * {@link MembershipCaseApplication#classesSupplier} but never Dagger-bound; C-COMPOSE step 6.2
 * rejects it as an unsupported feature before any catalog or manual match is attempted.
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
