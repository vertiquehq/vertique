// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.contract;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/** TP-007's resource for {@link ContractCApi}, whose sole operation's id is {@code opC}. */
@Path("/contract/c")
public class ContractCResource {

    /** Public {@code @Inject} constructor. */
    @Inject
    public ContractCResource() {}

    /**
     * Handles {@code GET /contract/c}, whose default operationId is {@code opC}.
     *
     * @return the fixed body {@code "c"}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String opC() {
        return "c";
    }
}
