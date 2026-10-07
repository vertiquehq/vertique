// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.disclosure.it.hidden;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.security.authz.AccessPolicy;
import dev.vertique.security.authz.Authorized;

/**
 * The protected twin of {@link AccountsFixedApi}: the same application name, path, and resource,
 * with a protected document. A component registers this interface or {@link AccountsFixedApi},
 * never both.
 */
@ApiDocs(
        policy = ProtectedAccountsFixedApi.AuthenticatedDocsPolicy.class,
        securityScheme = AccountsApplication.SECURITY_SCHEME)
@RestApplication(
        name = AccountsApplication.NAME,
        path = AccountsApplication.PATH,
        resources = FixedAccountsResource.class)
public interface ProtectedAccountsFixedApi {
    /** Any authenticated reader may read the document. */
    @Authorized
    public interface AuthenticatedDocsPolicy extends AccessPolicy {}
}
