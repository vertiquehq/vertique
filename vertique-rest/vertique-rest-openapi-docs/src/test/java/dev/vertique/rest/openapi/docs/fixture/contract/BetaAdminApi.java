// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.contract;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.security.authz.AccessPolicy;
import jakarta.annotation.security.PermitAll;

/**
 * The application {@code beta} at {@value BetaApi#PATH}, listing {@link BetaAdminUsersResource}
 * ({@code GET /admin/users}). It names no contract and carries no {@code @OpenAPIDefinition}; its
 * contract location comes only from configuration.
 */
@ApiDocs(policy = BetaAdminApi.PublicDocsPolicy.class)
@RestApplication(name = BetaApi.NAME, path = BetaApi.PATH, resources = BetaAdminUsersResource.class)
public interface BetaAdminApi {
    /** Anyone may read the document. */
    @PermitAll
    public interface PublicDocsPolicy extends AccessPolicy {}
}
