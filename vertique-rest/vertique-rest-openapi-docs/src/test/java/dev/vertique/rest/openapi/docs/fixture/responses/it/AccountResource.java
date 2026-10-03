// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.responses.it;

import dev.vertique.core.json.JsonProfile;
import dev.vertique.rest.openapi.docs.fixture.responses.dto.Account;
import io.vertx.core.Future;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * The resource {@value #ROUTE} of {@link AccountsApi}, serialized under the {@value
 * SnakeOutputProfileModule#SNAKE_OUTPUT_PROFILE} profile its class selects.
 *
 * <p>{@code POST /accounts} (operation {@value #CREATE}, the method name) binds an {@link Account}
 * body and answers {@code 200} with a new account carrying all three members: the request's display
 * name and password, and the identifier {@value #CREATED_ID}. The password is write-only, so the
 * response body carries only {@code display_name} and {@code id}. No response is declared.
 */
@Path(AccountResource.ROUTE)
@JsonProfile(SnakeOutputProfileModule.SNAKE_OUTPUT_PROFILE)
public class AccountResource {

    /** The resource path, relative to the mount. */
    public static final String ROUTE = "/accounts";

    /** The operation id of {@link #create}. */
    public static final String CREATE = "create";

    /** The identifier every created account carries. */
    public static final String CREATED_ID = "acct-1";

    /** Creates the resource. */
    public AccountResource() {}

    /**
     * Handles {@code POST /accounts}.
     *
     * @param account the body
     * @return the created account, with every member set
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Future<Account> create(Account account) {
        Account created = new Account();
        created.setDisplayName(account.getDisplayName());
        created.setPassword(account.getPassword());
        created.setId(CREATED_ID);
        return Future.succeededFuture(created);
    }
}
