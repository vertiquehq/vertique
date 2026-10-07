// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.conformance.corpus;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.security.authz.AccessPolicy;
import dev.vertique.security.authz.Authorized;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;

/**
 * Declares the security scheme kinds application {@value CorpusDocuments#SCHEMES} with a document
 * protected by {@value CorpusDocuments#BEARER_AUTH}, which any authenticated caller may read;
 * {@link PublicSchemesApi} declares it with a public one.
 */
@ApiDocs(policy = ProtectedSchemesApi.AuthenticatedDocsPolicy.class, securityScheme = CorpusDocuments.BEARER_AUTH)
@OpenAPIDefinition(info = @Info(title = "Conformance schemes", version = CorpusDocuments.VERSION))
@RestApplication(
        name = CorpusDocuments.SCHEMES,
        path = CorpusDocuments.SCHEMES_PATH,
        resources = SchemeKindsResource.class)
public interface ProtectedSchemesApi {
    /** Any authenticated reader may read the document. */
    @Authorized
    public interface AuthenticatedDocsPolicy extends AccessPolicy {}
}
