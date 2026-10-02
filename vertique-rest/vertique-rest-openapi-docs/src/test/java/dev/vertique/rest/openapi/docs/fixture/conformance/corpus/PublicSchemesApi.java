// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.conformance.corpus;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;

/**
 * Declares the security scheme kinds application {@value CorpusDocuments#SCHEMES} with a public
 * document; {@link ProtectedSchemesApi} declares it with a protected one.
 */
@ApiDocs(access = ApiDocs.Access.PUBLIC)
@OpenAPIDefinition(info = @Info(title = "Conformance schemes", version = CorpusDocuments.VERSION))
@RestApplication(
        name = CorpusDocuments.SCHEMES,
        path = CorpusDocuments.SCHEMES_PATH,
        resources = SchemeKindsResource.class)
public interface PublicSchemesApi {}
