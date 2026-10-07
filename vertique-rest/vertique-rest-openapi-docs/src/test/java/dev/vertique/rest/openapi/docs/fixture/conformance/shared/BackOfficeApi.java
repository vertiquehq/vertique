// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.conformance.shared;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.security.authz.AccessPolicy;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import jakarta.annotation.security.RolesAllowed;

/**
 * The documented application {@value #NAME} at {@value #PATH}, listing {@link BackOfficeOrderResource}.
 * Its declaring interface carries {@code @ApiDocs(policy = RolesDocsPolicy.class,
 * securityScheme = "bearerAuth")}: its document is served only to a caller the {@code bearerAuth} handler
 * authenticates and who holds {@code admin}, the same guard every operation of the resource carries.
 * The interface declares the document's {@code info}.
 */
@ApiDocs(policy = BackOfficeApi.RolesDocsPolicy.class, securityScheme = BackOfficeApi.SECURITY_SCHEME)
@OpenAPIDefinition(info = @Info(title = "Back Office", version = "1.0"))
@RestApplication(name = BackOfficeApi.NAME, path = BackOfficeApi.PATH, resources = BackOfficeOrderResource.class)
public interface BackOfficeApi {

    /** The application's name, which also names its document. */
    String NAME = "management";

    /** The application's path. */
    String PATH = "/api/mgmt";

    /** The security scheme guarding the document and every operation. */
    String SECURITY_SCHEME = "bearerAuth";

    /** The role allowed to read the document and call every operation. */
    String ROLE = "admin";

    /** Readers holding one of the listed roles may read the document. */
    @RolesAllowed({BackOfficeApi.ROLE})
    public interface RolesDocsPolicy extends AccessPolicy {}
}
