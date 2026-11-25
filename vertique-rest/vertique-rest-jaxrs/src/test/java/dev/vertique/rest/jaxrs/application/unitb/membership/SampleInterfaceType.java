// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.unitb.membership;

/**
 * Listed-only type: a plain interface. Listed by
 * {@link MembershipViolationRegistrations#interfaceCaseRegistration} but never Dagger-bound;
 * the composer rejects it as not a concrete root resource before any catalog or manual match
 * is attempted.
 */
public interface SampleInterfaceType {}
