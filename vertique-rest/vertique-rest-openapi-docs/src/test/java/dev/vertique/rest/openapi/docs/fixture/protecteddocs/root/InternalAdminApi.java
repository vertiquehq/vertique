// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.protecteddocs.root;

import dev.vertique.rest.jaxrs.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;

/**
 * The root application {@code internal} at {@code /}, with discovery membership, whose protected
 * document is readable only by a caller the {@value #SECURITY_SCHEME} scheme authenticates and who
 * holds the {@value #ROLE} role.
 */
@ApiDocs(
        access = ApiDocs.Access.PROTECTED,
        securityScheme = InternalAdminApi.SECURITY_SCHEME,
        rolesAllowed = {InternalAdminApi.ROLE})
@OpenAPIDefinition(info = @Info(title = "Internal", version = "1.0"))
@RestApplication(name = InternalAdminApi.NAME, path = InternalAdminApi.PATH, discover = true)
public interface InternalAdminApi {

    /** The application's name, which also names its document. */
    String NAME = "internal";

    /** The application's path: the root. */
    String PATH = "/";

    /** The security scheme guarding the document routes. */
    String SECURITY_SCHEME = "bearerAuth";

    /** The role allowed to read the document. */
    String ROLE = "admin";
}
