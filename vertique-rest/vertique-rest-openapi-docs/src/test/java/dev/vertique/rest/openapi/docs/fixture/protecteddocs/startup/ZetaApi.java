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
 * Application {@code zeta} at {@code /api/zeta}, whose valid protected document names the scheme
 * {@value StartupSchemeHandlers#BEARER_AUTH} and the role {@code admin}; its name orders it after
 * {@code management}.
 */
@ApiDocs(policy = ZetaApi.RolesDocsPolicy.class, securityScheme = StartupSchemeHandlers.BEARER_AUTH)
@OpenAPIDefinition(info = @Info(title = "Zeta API", version = "1.0"))
@RestApplication(name = "zeta", path = "/api/zeta", resources = ZetaStatusResource.class)
public interface ZetaApi {
    /** Readers holding one of the listed roles may read the document. */
    @RolesAllowed({"admin"})
    public interface RolesDocsPolicy extends AccessPolicy {}
}
