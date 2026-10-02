// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.protecteddocs.startup;

import dev.vertique.rest.jaxrs.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;

/**
 * Application {@code alpha} at {@code /api/alpha}, whose valid protected document names the scheme
 * {@value StartupSchemeHandlers#BEARER_AUTH} and the role {@code admin}; its name orders it before
 * {@code management}.
 */
@ApiDocs(
        access = ApiDocs.Access.PROTECTED,
        securityScheme = StartupSchemeHandlers.BEARER_AUTH,
        rolesAllowed = {"admin"})
@OpenAPIDefinition(info = @Info(title = "Alpha API", version = "1.0"))
@RestApplication(name = "alpha", path = "/api/alpha", resources = AlphaStatusResource.class)
public interface AlphaApi {}
