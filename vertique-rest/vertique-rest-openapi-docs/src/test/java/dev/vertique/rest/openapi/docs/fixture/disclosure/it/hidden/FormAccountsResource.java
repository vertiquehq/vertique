// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.disclosure.it.hidden;

import dev.vertique.rest.openapi.docs.fixture.disclosure.dto.AccountFormZx;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;

/**
 * The resource of {@link AccountsFormApi}: {@code POST /accounts} with a {@link AccountFormZx} body,
 * which holds both a {@code @Hidden} member and a member of a {@code @Hidden} type.
 */
@Path(AccountsApplication.ROUTE)
public class FormAccountsResource {

    /** Creates the resource. */
    public FormAccountsResource() {}

    /**
     * Handles {@code POST /accounts}; answers with an empty response.
     *
     * @param account the body
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public void createAccountZx(AccountFormZx account) {
        // Nothing to do: a request only proves the body binds.
    }
}
