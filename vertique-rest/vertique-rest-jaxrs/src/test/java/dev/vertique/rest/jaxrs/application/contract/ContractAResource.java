// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.contract;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/** TP-007's resource for {@link ContractAApi}, whose sole operation's id is {@code opA}. */
@Path("/contract/a")
public class ContractAResource {

    /** Public {@code @Inject} constructor. */
    @Inject
    public ContractAResource() {}

    /**
     * Handles {@code GET /contract/a}, whose default operationId is {@code opA}.
     *
     * @return the fixed body {@code "a"}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String opA() {
        return "a";
    }
}
