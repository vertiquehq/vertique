// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.security.authz.AccessPolicy;
import jakarta.annotation.security.PermitAll;

/**
 * The documented application {@code public} at {@code /api/public}: its declaring interface carries
 * {@code @ApiDocs(policy = PublicDocsPolicy.class)}, so its document is served publicly. Carries no
 * {@code @OpenAPIDefinition}; the document's {@code info} comes from configuration.
 */
@ApiDocs(policy = PublicApi.PublicDocsPolicy.class)
@RestApplication(name = PublicApi.NAME, path = PublicApi.PATH, resources = CatalogResource.class)
public interface PublicApi {

    /** The application's name, which also names its document. */
    String NAME = "public";

    /** The application's path. */
    String PATH = "/api/public";

    /** The application's mount path, as its registration and publication report it. */
    String MOUNT_PATH = PATH + "/*";

    /** Anyone may read the document. */
    @PermitAll
    public interface PublicDocsPolicy extends AccessPolicy {}
}
