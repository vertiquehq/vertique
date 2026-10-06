// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.protecteddocs.shared;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.rest.openapi.docs.fixture.CatalogResource;
import dev.vertique.security.authz.AccessPolicy;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import jakarta.annotation.security.PermitAll;

/**
 * The documented application {@value #NAME} at {@value #PATH}, listing {@link CatalogResource}. Its
 * declaring interface carries {@code @ApiDocs(policy = PublicDocsPolicy.class)}, so its document is served to every
 * caller, and declares the document's {@code info}, so the document needs no configuration.
 */
@ApiDocs(policy = OpenCatalogApi.PublicDocsPolicy.class)
@OpenAPIDefinition(info = @Info(title = "Open Catalog", version = "1.0"))
@RestApplication(name = OpenCatalogApi.NAME, path = OpenCatalogApi.PATH, resources = CatalogResource.class)
public interface OpenCatalogApi {

    /** The application's name, which also names its document. */
    String NAME = "public";

    /** The application's path. */
    String PATH = "/api/public";

    /** Anyone may read the document. */
    @PermitAll
    public interface PublicDocsPolicy extends AccessPolicy {}
}
