// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.apidocs;

import dev.vertique.examples.apidocs.resource.CatalogResource;
import dev.vertique.rest.jaxrs.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;

/**
 * The public catalog application, mounted at {@code /api/public}.
 *
 * <p>{@link ApiDocs} with {@link ApiDocs.Access#PUBLIC} publishes its OpenAPI document to every
 * caller at {@code /apidocs/public/openapi.json} and {@code .yaml}. The document's {@code info} comes
 * from {@link OpenAPIDefinition} on this interface; the shipped configuration adds only the
 * document's server URL.
 */
@RestApplication(
        name = "public",
        path = "/api/public",
        resources = {CatalogResource.class})
@ApiDocs(access = ApiDocs.Access.PUBLIC)
@OpenAPIDefinition(info = @Info(title = "Catalog API", version = "1.0"))
public interface PublicApi {}
