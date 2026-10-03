// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.contract;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;

/**
 * The application {@code catalog} declared as {@link CatalogApi} is, but also listing {@link
 * ThreeSegmentsProbeResource}, whose {@code GET /{a}/{b}/{c}} matches a document URL whenever the
 * documentation prefix lies directly under the catalog's mount path.
 */
@ApiDocs(access = ApiDocs.Access.PUBLIC)
@OpenAPIDefinition(info = @Info(title = CatalogApi.TITLE, version = CatalogApi.VERSION))
@RestApplication(
        name = CatalogApi.NAME,
        path = CatalogApi.PATH,
        resources = {CatalogEntryResource.class, ThreeSegmentsProbeResource.class})
public interface ThreeSegmentsCatalogApi {}
