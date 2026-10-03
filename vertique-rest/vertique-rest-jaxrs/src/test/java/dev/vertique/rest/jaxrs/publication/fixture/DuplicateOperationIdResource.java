// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication.fixture;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * T006 TP-006(b)'s IT-only fixture: a second resource, at a distinct path but sharing
 * {@link MgmtResource#echo()}'s method name (and so its default operation id, {@code "echo"}),
 * added to the hand-built {@code /api/mgmt/*} mount's resource set to trigger the registrar's
 * existing cross-resource duplicate-operationId detection (guard: this detection predates T006).
 */
@Path("/echo-duplicate")
public class DuplicateOperationIdResource {

    /**
     * Declares the same operation id as {@link MgmtResource#echo()}.
     *
     * @return a fixed body, never served
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String echo() {
        return "duplicate";
    }
}
