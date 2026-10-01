// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.disclosure.it.hidden;

import dev.vertique.rest.openapi.docs.fixture.disclosure.dto.AccountHiddenFieldZx;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;

/**
 * The resource of {@link AccountsHiddenFieldApi}: {@code POST /accounts} with a {@link AccountHiddenFieldZx} body,
 * whose member {@code backdoorZx} carries {@code @Hidden} only.
 */
@Path(AccountsApplication.ROUTE)
public class HiddenFieldAccountsResource {

    /** Creates the resource. */
    public HiddenFieldAccountsResource() {}

    /**
     * Handles {@code POST /accounts}; answers with an empty response.
     *
     * @param account the body
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public void createAccountZx(AccountHiddenFieldZx account) {
        // Nothing to do: a request only proves the body binds.
    }
}
