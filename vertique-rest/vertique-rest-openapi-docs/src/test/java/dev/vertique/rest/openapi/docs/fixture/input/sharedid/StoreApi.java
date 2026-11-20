// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.input.sharedid;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.security.authz.AccessPolicy;
import jakarta.annotation.security.PermitAll;

/**
 * The documented application {@code store} at {@code /api/store}, listing {@link ShelfResource}. Its
 * document is public.
 */
@ApiDocs(policy = StoreApi.PublicDocsPolicy.class)
@RestApplication(name = StoreApi.NAME, path = StoreApi.PATH, resources = ShelfResource.class)
public interface StoreApi {

    /** The application's name, which also names its document. */
    String NAME = "store";

    /** The application's path. */
    String PATH = "/api/store";

    /** Anyone may read the document. */
    @PermitAll
    public interface PublicDocsPolicy extends AccessPolicy {}
}
