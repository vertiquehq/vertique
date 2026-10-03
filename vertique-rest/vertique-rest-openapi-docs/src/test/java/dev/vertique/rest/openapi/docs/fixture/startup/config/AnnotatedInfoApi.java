// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.startup.config;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.rest.openapi.docs.fixture.CatalogResource;
import dev.vertique.rest.openapi.docs.fixture.PublicApi;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;

/**
 * Application {@code public} at {@code /api/public} listing {@link CatalogResource}, documented
 * publicly, whose declaring interface itself carries a complete {@code info}: title
 * {@code Annotated}, version {@code 2}, description {@code From code}.
 */
@OpenAPIDefinition(info = @Info(title = "Annotated", version = "2", description = "From code"))
@ApiDocs(access = ApiDocs.Access.PUBLIC)
@RestApplication(name = PublicApi.NAME, path = PublicApi.PATH, resources = CatalogResource.class)
public interface AnnotatedInfoApi {}
