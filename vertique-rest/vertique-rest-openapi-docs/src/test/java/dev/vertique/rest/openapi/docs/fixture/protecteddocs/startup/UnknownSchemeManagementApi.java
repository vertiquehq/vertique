// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.protecteddocs.startup;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;

/**
 * Application {@code management} at {@code /api/management}, whose protected document names the
 * scheme {@value StartupSchemeHandlers#NOPE}, which no registered handler has, and the role
 * {@code admin}.
 */
@ApiDocs(
        access = ApiDocs.Access.PROTECTED,
        securityScheme = StartupSchemeHandlers.NOPE,
        rolesAllowed = {"admin"})
@OpenAPIDefinition(info = @Info(title = "Management API", version = "1.0"))
@RestApplication(name = "management", path = "/api/management", resources = ManagementStatusResource.class)
public interface UnknownSchemeManagementApi {}
