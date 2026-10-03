// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.disclosure.it.hidden;

import dev.vertique.rest.openapi.docs.fixture.disclosure.dto.AccountCtorZx;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;

/**
 * The resource of {@link AccountsCtorApi}: {@code POST /accounts} with a {@link AccountCtorZx} body,
 * whose creator parameter {@code tokenZx} carries {@code @Schema(hidden = true)}.
 */
@Path(AccountsApplication.ROUTE)
public class CtorAccountsResource {

    /** Creates the resource. */
    public CtorAccountsResource() {}

    /**
     * Handles {@code POST /accounts}; answers with an empty response.
     *
     * @param account the body
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public void createAccountZx(AccountCtorZx account) {
        // Nothing to do: a request only proves the body binds.
    }
}
