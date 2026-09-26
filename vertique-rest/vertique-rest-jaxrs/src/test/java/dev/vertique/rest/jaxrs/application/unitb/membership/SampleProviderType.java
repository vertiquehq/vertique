// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.unitb.membership;

import jakarta.ws.rs.ext.Provider;

/**
 * TP-005 case 2's listed-only type: a class annotated {@code @jakarta.ws.rs.ext.Provider}. Listed
 * in {@link MembershipCaseApplication#classesSupplier} but never Dagger-bound; C-COMPOSE step 6.2
 * rejects it as an unsupported provider before any catalog or manual match is attempted.
 */
@Provider
public class SampleProviderType {

    /** Public no-arg constructor; never invoked by the composer. */
    public SampleProviderType() {}
}
