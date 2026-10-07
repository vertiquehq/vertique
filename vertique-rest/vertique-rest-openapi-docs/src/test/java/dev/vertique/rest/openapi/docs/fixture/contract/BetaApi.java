// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.contract;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.security.authz.AccessPolicy;
import jakarta.annotation.security.PermitAll;

/**
 * The documented application {@value #NAME} at {@value #PATH}, listing {@link BetaResource}
 * ({@code GET /b}). It names no contract and carries no {@code @OpenAPIDefinition}; its contract
 * location comes only from configuration.
 */
@ApiDocs(policy = BetaApi.PublicDocsPolicy.class)
@RestApplication(name = BetaApi.NAME, path = BetaApi.PATH, resources = BetaResource.class)
public interface BetaApi {

    /** The application's name, which also names its document. */
    String NAME = "beta";

    /** The application's path. */
    String PATH = "/api/beta";

    /** The application's mount path, as its registration and publication report it. */
    String MOUNT_PATH = PATH + "/*";

    /** Anyone may read the document. */
    @PermitAll
    public interface PublicDocsPolicy extends AccessPolicy {}
}
