// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.responses.it;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.security.authz.AccessPolicy;
import jakarta.annotation.security.PermitAll;

/**
 * The documented application {@code receiptsexplicit} at {@code /api} (mount {@code /api/*}),
 * listing {@link ReceiptExplicitResource}. Its one operation returns {@code Response} and declares
 * {@code ReceiptZx} as its {@code 200} content. Its document is public; its {@code info} comes from
 * configuration.
 */
@ApiDocs(policy = ReceiptsExplicitApi.PublicDocsPolicy.class)
@RestApplication(
        name = ReceiptsExplicitApi.NAME,
        path = ReceiptsExplicitApi.PATH,
        resources = ReceiptExplicitResource.class)
public interface ReceiptsExplicitApi {

    /** The application's name, which also names its document. */
    String NAME = "receiptsexplicit";

    /** The application's path. */
    String PATH = "/api";

    /** Anyone may read the document. */
    @PermitAll
    public interface PublicDocsPolicy extends AccessPolicy {}
}
