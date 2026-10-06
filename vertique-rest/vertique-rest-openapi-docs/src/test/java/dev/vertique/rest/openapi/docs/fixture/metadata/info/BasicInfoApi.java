// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.metadata.info;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.security.authz.AccessPolicy;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import jakarta.annotation.security.PermitAll;

/**
 * Application {@code public} at {@code /api/public}, documented publicly, whose declaring interface
 * sets only the {@code @Info} title {@code Basic}, version {@code 1}, and description {@code Only
 * three}; every other member keeps its default.
 */
@OpenAPIDefinition(info = @Info(title = "Basic", version = "1", description = "Only three"))
@ApiDocs(policy = BasicInfoApi.PublicDocsPolicy.class)
@RestApplication(
        name = InfoRegistrations.PUBLIC_NAME,
        path = InfoRegistrations.PUBLIC_PATH,
        resources = InfoPingResource.class)
public interface BasicInfoApi {
    /** Anyone may read the document. */
    @PermitAll
    public interface PublicDocsPolicy extends AccessPolicy {}
}
