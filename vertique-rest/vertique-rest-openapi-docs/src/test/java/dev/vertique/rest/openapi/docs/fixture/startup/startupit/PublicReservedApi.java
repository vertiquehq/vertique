// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.startup.startupit;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.rest.openapi.docs.fixture.CatalogResource;
import dev.vertique.rest.openapi.docs.fixture.PublicApi;

/**
 * The documented application {@code public} at {@code /api/public} listing {@link CatalogResource}
 * and {@link ReservedJsonResource}, whose operation id is the synthetic id of the application's own
 * document. Carries {@code @ApiDocs(access = PUBLIC)} and no {@code @OpenAPIDefinition}.
 */
@ApiDocs(access = ApiDocs.Access.PUBLIC)
@RestApplication(
        name = PublicApi.NAME,
        path = PublicApi.PATH,
        resources = {CatalogResource.class, ReservedJsonResource.class})
public interface PublicReservedApi {}
