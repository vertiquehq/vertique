// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.responses.it;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;

/**
 * The documented application {@code tiers} at {@code /api} (mount {@code /api/*}), listing {@link
 * TierResource}. Its one operation returns {@code Future<TierReceiptZx>}, which reaches an enum
 * constant carrying {@code @Schema(hidden = true)}. Its document is public; its {@code info} comes
 * from configuration.
 */
@ApiDocs(access = ApiDocs.Access.PUBLIC)
@RestApplication(name = TiersApi.NAME, path = TiersApi.PATH, resources = TierResource.class)
public interface TiersApi {

    /** The application's name, which also names its document. */
    String NAME = "tiers";

    /** The application's path. */
    String PATH = "/api";
}
