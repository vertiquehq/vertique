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
 * The application {@code catalog} declared as {@link CatalogApi} is, but also listing {@link
 * ReservedPartnerJsonResource}, whose operation uses the synthetic operation id of the {@code
 * partner} document's JSON form.
 */
@ApiDocs(policy = ReservedIdCatalogApi.PublicDocsPolicy.class)
@OpenAPIDefinition(info = @Info(title = CatalogApi.TITLE, version = CatalogApi.VERSION))
@RestApplication(
        name = CatalogApi.NAME,
        path = CatalogApi.PATH,
        resources = {CatalogEntryResource.class, ReservedPartnerJsonResource.class})
public interface ReservedIdCatalogApi {
    /** Anyone may read the document. */
    @PermitAll
    public interface PublicDocsPolicy extends AccessPolicy {}
}
