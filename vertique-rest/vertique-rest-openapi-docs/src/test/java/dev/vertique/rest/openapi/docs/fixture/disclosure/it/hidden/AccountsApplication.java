// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.disclosure.it.hidden;

/**
 * The shared facts of the application {@code accounts}: every composition declares it at the same
 * name and path with one resource whose one operation, {@value #OPERATION_ID}, is {@code POST}
 * {@value #ROUTE} with a JSON body and no result. Only the body type differs between compositions.
 */
public final class AccountsApplication {

    /** The application's name, which also names its document. */
    public static final String NAME = "accounts";

    /** The application's path. */
    public static final String PATH = "/api/accounts";

    /** The operation's route, relative to the mount. */
    public static final String ROUTE = "/accounts";

    /** The operation id of every composition's one operation. */
    public static final String OPERATION_ID = "createAccountZx";

    /** The security scheme that would guard a protected document's routes. */
    public static final String SECURITY_SCHEME = "bearerAuth";

    private AccountsApplication() {}
}
