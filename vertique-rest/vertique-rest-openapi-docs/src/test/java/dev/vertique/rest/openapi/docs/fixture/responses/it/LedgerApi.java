// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.responses.it;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;

/**
 * The documented application {@code ledger} at {@code /api} (mount {@code /api/*}), listing {@link
 * LedgerResource}. Its one operation returns {@code Future<LedgerZx>}, which reaches a type
 * annotated {@code @Hidden}. Its document is public; its {@code info} comes from configuration.
 */
@ApiDocs(access = ApiDocs.Access.PUBLIC)
@RestApplication(name = LedgerApi.NAME, path = LedgerApi.PATH, resources = LedgerResource.class)
public interface LedgerApi {

    /** The application's name, which also names its document. */
    String NAME = "ledger";

    /** The application's path. */
    String PATH = "/api";
}
