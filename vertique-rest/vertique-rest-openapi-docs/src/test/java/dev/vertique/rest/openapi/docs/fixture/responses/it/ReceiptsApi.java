// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.responses.it;

import dev.vertique.rest.jaxrs.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;

/**
 * The documented application {@code receipts} at {@code /api} (mount {@code /api/*}), listing
 * {@link ReceiptResource}. Its one operation returns {@code Future<ReceiptZx>}, whose field carries
 * {@code @Hidden} only. Its document is public; its {@code info} comes from configuration.
 */
@ApiDocs(access = ApiDocs.Access.PUBLIC)
@RestApplication(name = ReceiptsApi.NAME, path = ReceiptsApi.PATH, resources = ReceiptResource.class)
public interface ReceiptsApi {

    /** The application's name, which also names its document. */
    String NAME = "receipts";

    /** The application's path. */
    String PATH = "/api";
}
