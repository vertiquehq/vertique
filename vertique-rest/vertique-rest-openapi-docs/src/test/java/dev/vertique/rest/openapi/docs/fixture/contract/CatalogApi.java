// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.contract;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.security.authz.AccessPolicy;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import jakarta.annotation.security.PermitAll;

/**
 * The documented application {@value #NAME} at {@value #PATH}, listing {@link CatalogEntryResource}.
 * It names no contract, so it validates against the global {@code jaxrs.openapiPath} and its
 * document is generated, with the {@code info} its declaring interface carries.
 */
@ApiDocs(policy = CatalogApi.PublicDocsPolicy.class)
@OpenAPIDefinition(info = @Info(title = CatalogApi.TITLE, version = CatalogApi.VERSION))
@RestApplication(name = CatalogApi.NAME, path = CatalogApi.PATH, resources = CatalogEntryResource.class)
public interface CatalogApi {

    /** The application's name, which also names its document. */
    String NAME = "catalog";

    /** The application's path. */
    String PATH = "/api/catalog";

    /** The application's mount path, as its registration and publication report it. */
    String MOUNT_PATH = PATH + "/*";

    /** The annotated {@code info.title}. */
    String TITLE = "Catalog";

    /** The annotated {@code info.version}. */
    String VERSION = "1.0";

    /** Anyone may read the document. */
    @PermitAll
    public interface PublicDocsPolicy extends AccessPolicy {}
}
