// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.disclosure.it.hidden;

import dev.vertique.rest.jaxrs.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;

/**
 * The application {@code accounts} at {@code /api/accounts} composed with {@link
 * HiddenTypeAccountsResource}, whose body is {@code AccountHiddenTypeZx}. Its document is public; its
 * {@code info} comes from configuration.
 */
@ApiDocs(access = ApiDocs.Access.PUBLIC)
@RestApplication(
        name = AccountsApplication.NAME,
        path = AccountsApplication.PATH,
        resources = HiddenTypeAccountsResource.class)
public interface AccountsHiddenTypeApi {}
