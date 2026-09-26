// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.unitb.membership;

/**
 * TP-005 case 10's listed-only type: a plain interface. Listed in
 * {@link MembershipCaseApplication#classesSupplier} but never Dagger-bound; C-COMPOSE step 6.3
 * rejects it as not a concrete root resource before any catalog or manual match is attempted.
 */
public interface SampleInterfaceType {}
