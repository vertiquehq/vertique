// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.unitb.membership;

/**
 * TP-005 case 12's listed-only type: a concrete class with no {@code @Path} anywhere in its
 * hierarchy, so it has no effective {@code @Path}. Listed in
 * {@link MembershipCaseApplication#classesSupplier} but never Dagger-bound; C-COMPOSE step 6.3
 * rejects it as not a concrete root resource before any catalog or manual match is attempted.
 */
public class SampleNoPathResource {

    /** Public no-arg constructor; never invoked by the composer. */
    public SampleNoPathResource() {}
}
