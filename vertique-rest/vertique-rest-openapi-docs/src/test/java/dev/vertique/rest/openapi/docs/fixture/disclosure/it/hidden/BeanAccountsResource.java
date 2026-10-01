// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.disclosure.it.hidden;

import dev.vertique.rest.openapi.docs.fixture.disclosure.dto.AccountBeanZx;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;

/**
 * The resource of {@link AccountsBeanApi}: {@code POST /accounts} with a {@link AccountBeanZx} body,
 * a JavaBean whose getter {@code getPinZx} carries {@code @Schema(hidden = true)}.
 */
@Path(AccountsApplication.ROUTE)
public class BeanAccountsResource {

    /** Creates the resource. */
    public BeanAccountsResource() {}

    /**
     * Handles {@code POST /accounts}; answers with an empty response.
     *
     * @param account the body
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public void createAccountZx(AccountBeanZx account) {
        // Nothing to do: a request only proves the body binds.
    }
}
