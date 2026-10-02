// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.contract;

import dev.vertique.rest.jaxrs.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;

/**
 * The documented application {@value #NAME} at {@value #PATH}, listing {@link BetaResource}
 * ({@code GET /b}). It names no contract and carries no {@code @OpenAPIDefinition}; its contract
 * location comes only from configuration.
 */
@ApiDocs(access = ApiDocs.Access.PUBLIC)
@RestApplication(name = BetaApi.NAME, path = BetaApi.PATH, resources = BetaResource.class)
public interface BetaApi {

    /** The application's name, which also names its document. */
    String NAME = "beta";

    /** The application's path. */
    String PATH = "/api/beta";

    /** The application's mount path, as its registration and publication report it. */
    String MOUNT_PATH = PATH + "/*";
}
