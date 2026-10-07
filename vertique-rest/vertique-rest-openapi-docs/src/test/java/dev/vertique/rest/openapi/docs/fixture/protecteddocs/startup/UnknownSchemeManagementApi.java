// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.protecteddocs.startup;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.security.authz.AccessPolicy;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import jakarta.annotation.security.RolesAllowed;

/**
 * Application {@code management} at {@code /api/management}, whose protected document names the
 * scheme {@value StartupSchemeHandlers#NOPE}, which no registered handler has, and the role
 * {@code admin}.
 */
@ApiDocs(policy = UnknownSchemeManagementApi.RolesDocsPolicy.class, securityScheme = StartupSchemeHandlers.NOPE)
@OpenAPIDefinition(info = @Info(title = "Management API", version = "1.0"))
@RestApplication(name = "management", path = "/api/management", resources = ManagementStatusResource.class)
public interface UnknownSchemeManagementApi {
    /** Readers holding one of the listed roles may read the document. */
    @RolesAllowed({"admin"})
    public interface RolesDocsPolicy extends AccessPolicy {}
}
