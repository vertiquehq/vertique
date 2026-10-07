// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.apidocs;

import dev.vertique.examples.apidocs.resource.CatalogResource;
import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.security.authz.AccessPolicy;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import jakarta.annotation.security.PermitAll;

/**
 * The public catalog application, mounted at {@code /api/public}.
 *
 * <p>{@link ApiDocs} with a policy that permits everyone publishes its OpenAPI document to every
 * caller at {@code /apidocs/public/openapi.json} and {@code .yaml}. The document's {@code info} comes
 * from {@link OpenAPIDefinition} on this interface; the shipped configuration adds only the
 * document's server URL.
 */
@RestApplication(
        name = "public",
        path = "/api/public",
        resources = {CatalogResource.class})
@ApiDocs(policy = PublicApi.DocsPolicy.class)
@OpenAPIDefinition(info = @Info(title = "Catalog API", version = "1.0"))
public interface PublicApi {

    /** Every caller may read the document. */
    @PermitAll
    public interface DocsPolicy extends AccessPolicy {}
}
