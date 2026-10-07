// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.disclosure.it.hidden;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.security.authz.AccessPolicy;
import jakarta.annotation.security.PermitAll;

/**
 * The application {@code accounts} at {@code /api/accounts} composed with {@link
 * BeanAccountsResource}, whose body is {@code AccountBeanZx}. Its document is public; its
 * {@code info} comes from configuration.
 */
@ApiDocs(policy = AccountsBeanApi.PublicDocsPolicy.class)
@RestApplication(
        name = AccountsApplication.NAME,
        path = AccountsApplication.PATH,
        resources = BeanAccountsResource.class)
public interface AccountsBeanApi {
    /** Anyone may read the document. */
    @PermitAll
    public interface PublicDocsPolicy extends AccessPolicy {}
}
