// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.contract;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.security.authz.AccessPolicy;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import jakarta.annotation.security.PermitAll;

/**
 * The application {@code partner} declared as {@link PartnerApi} is, with the same path, resource,
 * and own contract, but whose declaring interface also carries an {@code @OpenAPIDefinition} with an
 * {@code info}, which a document served from its own contract must refuse. The annotated title is
 * the marker {@code zq7}, which no message may echo.
 */
@ApiDocs(policy = AnnotatedInfoPartnerApi.PublicDocsPolicy.class)
@OpenAPIDefinition(info = @Info(title = "zq7", version = "1"))
@RestApplication(
        name = PartnerApi.NAME,
        path = PartnerApi.PATH,
        resources = PartnerOrderResource.class,
        openapiPath = PartnerApi.OPENAPI_PATH)
public interface AnnotatedInfoPartnerApi {
    /** Anyone may read the document. */
    @PermitAll
    public interface PublicDocsPolicy extends AccessPolicy {}
}
