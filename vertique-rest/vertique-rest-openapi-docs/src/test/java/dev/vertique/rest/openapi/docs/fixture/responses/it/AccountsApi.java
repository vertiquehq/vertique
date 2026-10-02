// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.responses.it;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;

/**
 * The documented application {@code accounts} at {@code /api} (mount {@code /api/*}), listing
 * {@link AccountResource}, {@link ReportResource}. Its operations publish an asymmetric DTO under a
 * snake-case profile, a dynamic response, and declared content that differs from the return type.
 * Its document is public; its {@code info} comes from configuration.
 */
@ApiDocs(access = ApiDocs.Access.PUBLIC)
@RestApplication(
        name = AccountsApi.NAME,
        path = AccountsApi.PATH,
        resources = {AccountResource.class, ReportResource.class})
public interface AccountsApi {

    /** The application's name, which also names its document. */
    String NAME = "accounts";

    /** The application's path. */
    String PATH = "/api";
}
