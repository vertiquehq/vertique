// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.contract;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.security.authz.AccessPolicy;
import jakarta.annotation.security.PermitAll;

/**
 * The application {@code alpha} at {@value AlphaApi#PATH}, listing {@link AlphaCatalogResource}
 * ({@code GET /catalog}). It names no contract and carries no {@code @OpenAPIDefinition}; its
 * contract location comes only from configuration.
 */
@ApiDocs(policy = AlphaCatalogApi.PublicDocsPolicy.class)
@RestApplication(name = AlphaApi.NAME, path = AlphaApi.PATH, resources = AlphaCatalogResource.class)
public interface AlphaCatalogApi {
    /** Anyone may read the document. */
    @PermitAll
    public interface PublicDocsPolicy extends AccessPolicy {}
}
