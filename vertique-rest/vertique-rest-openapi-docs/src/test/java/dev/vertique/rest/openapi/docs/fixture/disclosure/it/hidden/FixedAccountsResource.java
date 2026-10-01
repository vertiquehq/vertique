// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.disclosure.it.hidden;

import dev.vertique.rest.openapi.docs.fixture.disclosure.dto.AccountFixedZx;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;

/**
 * The resource of {@link AccountsFixedApi}: {@code POST /accounts} with a {@link AccountFixedZx} body,
 * whose member {@code backdoorZx} carries both {@code @Hidden} and {@code @Schema(hidden = true)}.
 */
@Path(AccountsApplication.ROUTE)
public class FixedAccountsResource {

    /** Creates the resource. */
    public FixedAccountsResource() {}

    /**
     * Handles {@code POST /accounts}; answers with an empty response.
     *
     * @param account the body
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public void createAccountZx(AccountFixedZx account) {
        // Nothing to do: a request only proves the body binds.
    }
}
