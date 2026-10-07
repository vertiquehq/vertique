// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.contract;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.security.authz.AccessPolicy;
import jakarta.annotation.security.PermitAll;

/**
 * The documented application {@value #NAME} at {@value #PATH}, listing {@link AlphaResource}
 * ({@code GET /a}). It names no contract and carries no {@code @OpenAPIDefinition}; its contract
 * location comes only from configuration.
 */
@ApiDocs(policy = AlphaApi.PublicDocsPolicy.class)
@RestApplication(name = AlphaApi.NAME, path = AlphaApi.PATH, resources = AlphaResource.class)
public interface AlphaApi {

    /** The application's name, which also names its document. */
    String NAME = "alpha";

    /** The application's path. */
    String PATH = "/api/alpha";

    /** The application's mount path, as its registration and publication report it. */
    String MOUNT_PATH = PATH + "/*";

    /** Anyone may read the document. */
    @PermitAll
    public interface PublicDocsPolicy extends AccessPolicy {}
}
