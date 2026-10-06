// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.conformance.shared;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.security.authz.AccessPolicy;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import jakarta.annotation.security.PermitAll;

/**
 * The documented application {@value #NAME} at {@value #PATH}, listing {@link StorefrontResource}.
 * Its declaring interface carries {@code @ApiDocs(policy = PublicDocsPolicy.class)}, so its document is served to
 * every caller, and declares the document's {@code info}, so the document needs no configuration.
 */
@ApiDocs(policy = StorefrontApi.PublicDocsPolicy.class)
@OpenAPIDefinition(info = @Info(title = "Storefront", version = "1.0"))
@RestApplication(name = StorefrontApi.NAME, path = StorefrontApi.PATH, resources = StorefrontResource.class)
public interface StorefrontApi {

    /** The application's name, which also names its document. */
    String NAME = "public";

    /** The application's path. */
    String PATH = "/api/public";

    /** Anyone may read the document. */
    @PermitAll
    public interface PublicDocsPolicy extends AccessPolicy {}
}
