// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.responses.it;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.security.authz.AccessPolicy;
import jakarta.annotation.security.PermitAll;

/**
 * The documented application {@code hiddenop} at {@code /api} (mount {@code /api/*}), listing
 * {@link HiddenOperationResource}. One operation is hidden and returns {@code Future<ReceiptZx>};
 * the other is visible and returns {@code void}. Its document is public; its {@code info} comes
 * from configuration.
 */
@ApiDocs(policy = HiddenOpApi.PublicDocsPolicy.class)
@RestApplication(name = HiddenOpApi.NAME, path = HiddenOpApi.PATH, resources = HiddenOperationResource.class)
public interface HiddenOpApi {

    /** The application's name, which also names its document. */
    String NAME = "hiddenop";

    /** The application's path. */
    String PATH = "/api";

    /** Anyone may read the document. */
    @PermitAll
    public interface PublicDocsPolicy extends AccessPolicy {}
}
