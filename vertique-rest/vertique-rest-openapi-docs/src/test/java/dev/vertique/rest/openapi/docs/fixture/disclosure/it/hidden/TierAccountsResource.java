// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.disclosure.it.hidden;

import dev.vertique.rest.openapi.docs.fixture.disclosure.dto.AccountTierZx;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;

/**
 * The resource of {@link AccountsTierApi}: {@code POST /accounts} with a {@link AccountTierZx} body,
 * whose member {@code tier} has an enum type with a constant marked {@code @Schema(hidden = true)}.
 */
@Path(AccountsApplication.ROUTE)
public class TierAccountsResource {

    /** Creates the resource. */
    public TierAccountsResource() {}

    /**
     * Handles {@code POST /accounts}; answers with an empty response.
     *
     * @param account the body
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public void createAccountZx(AccountTierZx account) {
        // Nothing to do: a request only proves the body binds.
    }
}
