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
 * ThreeSegmentsProbeResource}, whose {@code GET /{a}/{b}/{c}} matches a document URL whenever the
 * documentation prefix lies directly under the catalog's mount path.
 */
@ApiDocs(policy = ThreeSegmentsCatalogApi.PublicDocsPolicy.class)
@OpenAPIDefinition(info = @Info(title = CatalogApi.TITLE, version = CatalogApi.VERSION))
@RestApplication(
        name = CatalogApi.NAME,
        path = CatalogApi.PATH,
        resources = {CatalogEntryResource.class, ThreeSegmentsProbeResource.class})
public interface ThreeSegmentsCatalogApi {
    /** Anyone may read the document. */
    @PermitAll
    public interface PublicDocsPolicy extends AccessPolicy {}
}
