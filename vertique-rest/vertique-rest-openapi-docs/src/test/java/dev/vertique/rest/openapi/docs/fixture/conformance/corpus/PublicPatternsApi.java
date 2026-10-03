// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.conformance.corpus;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.rest.openapi.docs.fixture.input.patterns.PatternsResource;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;

/**
 * Declares the patterns application {@value CorpusDocuments#PATTERNS} with a public document;
 * {@link ProtectedPatternsApi} declares it with a protected one.
 */
@ApiDocs(access = ApiDocs.Access.PUBLIC)
@OpenAPIDefinition(info = @Info(title = "Conformance patterns", version = CorpusDocuments.VERSION))
@RestApplication(
        name = CorpusDocuments.PATTERNS,
        path = CorpusDocuments.PATTERNS_PATH,
        resources = PatternsResource.class)
public interface PublicPatternsApi {}
