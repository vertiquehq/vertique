// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.unitb.membership;

import jakarta.ws.rs.Path;

/**
 * Listed-only type: an abstract class, carrying {@code @Path} so its failure is
 * unambiguously attributable to abstractness rather than a missing effective path. Listed by
 * {@link MembershipViolationRegistrations#abstractCaseRegistration} but never Dagger-bound;
 * the composer rejects it as not a concrete root resource before any catalog or manual match
 * is attempted.
 */
@Path("/sample-abstract")
public abstract class SampleAbstractType {}
