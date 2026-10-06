// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.metadata.it.hidden;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.security.authz.AccessPolicy;
import dev.vertique.security.authz.Authorized;

/**
 * The control application {@code visiblewrite} at {@code /api/visiblewrite}, with a protected
 * document, listing {@link VisibleWriteResource}: the write operation of {@link
 * MixedOperationsResource} with the same body type and the same hidden query binding, left visible.
 * Its protected rendering shows the root flags that operation causes when it is published.
 */
@ApiDocs(
        policy = ProtectedVisibleWriteApi.AuthenticatedDocsPolicy.class,
        securityScheme = ProtectedHiddenOperationsApi.SECURITY_SCHEME)
@RestApplication(
        name = ProtectedVisibleWriteApi.NAME,
        path = ProtectedVisibleWriteApi.PATH,
        resources = VisibleWriteResource.class)
public interface ProtectedVisibleWriteApi {

    /** The application's name, which also names its document. */
    String NAME = "visiblewrite";

    /** The application's path. */
    String PATH = "/api/visiblewrite";

    /** Any authenticated reader may read the document. */
    @Authorized
    public interface AuthenticatedDocsPolicy extends AccessPolicy {}
}
