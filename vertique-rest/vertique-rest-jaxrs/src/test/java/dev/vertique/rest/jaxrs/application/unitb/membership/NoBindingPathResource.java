// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.unitb.membership;

import jakarta.ws.rs.Path;

/**
 * TP-005 case 4's listed-only type: a concrete class with an effective {@code @Path}, but neither a
 * catalog entry nor a manual instance anywhere in the fixture graph. Listed in
 * {@link MembershipCaseApplication#classesSupplier}; C-COMPOSE step 6.7 rejects it as unbound
 * (zero catalog or manual matches).
 */
@Path("/no-binding")
public class NoBindingPathResource {

    /** Public no-arg constructor; never invoked by the composer (never bound anywhere). */
    public NoBindingPathResource() {}
}
