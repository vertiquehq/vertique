// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.contract;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/** TP-007's resource for {@link ContractBApi}, whose sole operation's id is {@code opB}. */
@Path("/contract/b")
public class ContractBResource {

    /** Public {@code @Inject} constructor. */
    @Inject
    public ContractBResource() {}

    /**
     * Handles {@code GET /contract/b}, whose default operationId is {@code opB}.
     *
     * @return the fixed body {@code "b"}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String opB() {
        return "b";
    }
}
