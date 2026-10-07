// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.startup.startupit;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.rest.openapi.docs.fixture.CatalogResource;
import dev.vertique.rest.openapi.docs.fixture.PublicApi;
import dev.vertique.security.authz.AccessPolicy;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import jakarta.annotation.security.PermitAll;

/**
 * The same declaration as {@link PublicApi} whose interface also declares its document's
 * {@code info}: application {@code public} at {@code /api/public} listing {@link CatalogResource},
 * with {@code @ApiDocs(policy = PublicDocsPolicy.class)} and {@code @OpenAPIDefinition(info = @Info(title =
 * "Catalog API", version = "1.0"))}, so its document can be published without any configuration.
 */
@ApiDocs(policy = AnnotatedPublicApi.PublicDocsPolicy.class)
@OpenAPIDefinition(info = @Info(title = "Catalog API", version = "1.0"))
@RestApplication(name = PublicApi.NAME, path = PublicApi.PATH, resources = CatalogResource.class)
public interface AnnotatedPublicApi {
    /** Anyone may read the document. */
    @PermitAll
    public interface PublicDocsPolicy extends AccessPolicy {}
}
