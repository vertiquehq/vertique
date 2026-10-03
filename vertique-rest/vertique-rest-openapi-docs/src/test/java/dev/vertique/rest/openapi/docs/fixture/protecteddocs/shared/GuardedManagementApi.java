// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.protecteddocs.shared;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;

/**
 * The documented application {@value #NAME} at {@value #PATH}, listing {@link TwinResource}. Its
 * declaring interface carries {@code @ApiDocs(access = PROTECTED, securityScheme = "bearerAuth",
 * rolesAllowed = {"admin"})}: its document is guarded exactly as a resource method annotated
 * {@code @SecurityRequirement(name = "bearerAuth")} and {@code @RolesAllowed("admin")}, which is how
 * {@link TwinResource#readTwin()} is annotated. The interface declares the document's {@code info}.
 */
@ApiDocs(
        access = ApiDocs.Access.PROTECTED,
        securityScheme = GuardedManagementApi.SECURITY_SCHEME,
        rolesAllowed = {GuardedManagementApi.ROLE})
@OpenAPIDefinition(info = @Info(title = "Guarded Management", version = "1.0"))
@RestApplication(name = GuardedManagementApi.NAME, path = GuardedManagementApi.PATH, resources = TwinResource.class)
public interface GuardedManagementApi {

    /** The application's name, which also names its document. */
    String NAME = "management";

    /** The application's path. */
    String PATH = "/api/mgmt";

    /** The security scheme guarding the document and the twin. */
    String SECURITY_SCHEME = "bearerAuth";

    /** The role allowed to read the document and the twin. */
    String ROLE = "admin";
}
