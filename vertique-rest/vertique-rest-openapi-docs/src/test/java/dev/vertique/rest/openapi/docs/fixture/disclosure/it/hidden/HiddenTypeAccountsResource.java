// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.disclosure.it.hidden;

import dev.vertique.rest.openapi.docs.fixture.disclosure.dto.AccountHiddenTypeZx;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;

/**
 * The resource of {@link AccountsHiddenTypeApi}: {@code POST /accounts} with a {@link AccountHiddenTypeZx} body,
 * whose member {@code audit} has a type marked {@code @Hidden}.
 */
@Path(AccountsApplication.ROUTE)
public class HiddenTypeAccountsResource {

    /** Creates the resource. */
    public HiddenTypeAccountsResource() {}

    /**
     * Handles {@code POST /accounts}; answers with an empty response.
     *
     * @param account the body
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public void createAccountZx(AccountHiddenTypeZx account) {
        // Nothing to do: a request only proves the body binds.
    }
}
