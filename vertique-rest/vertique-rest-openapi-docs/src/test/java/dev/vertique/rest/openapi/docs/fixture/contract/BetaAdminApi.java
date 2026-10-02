// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.contract;

import dev.vertique.rest.jaxrs.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;

/**
 * The application {@code beta} at {@value BetaApi#PATH}, listing {@link BetaAdminUsersResource}
 * ({@code GET /admin/users}). It names no contract and carries no {@code @OpenAPIDefinition}; its
 * contract location comes only from configuration.
 */
@ApiDocs(access = ApiDocs.Access.PUBLIC)
@RestApplication(name = BetaApi.NAME, path = BetaApi.PATH, resources = BetaAdminUsersResource.class)
public interface BetaAdminApi {}
