// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.unitb.membership;

import jakarta.ws.rs.container.DynamicFeature;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.FeatureContext;

/**
 * TP-005 case 9's listed-only type: a class implementing {@link DynamicFeature}. Listed in
 * {@link MembershipCaseApplication#classesSupplier} but never Dagger-bound; C-COMPOSE step 6.2
 * rejects it as an unsupported feature before any catalog or manual match is attempted.
 */
public class SampleDynamicFeatureType implements DynamicFeature {

    /** Public no-arg constructor; never invoked by the composer. */
    public SampleDynamicFeatureType() {}

    /**
     * Never invoked by the composer.
     *
     * @param resourceInfo the resource info
     * @param context      the feature context
     */
    @Override
    public void configure(ResourceInfo resourceInfo, FeatureContext context) {}
}
