// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.conformance.corpus;

import dev.vertique.rest.jaxrs.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.rest.openapi.docs.fixture.corpus.CorpusResource;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;

/**
 * Declares the request-body corpus application {@value CorpusDocuments#CORPUS} with a document
 * protected by {@value CorpusDocuments#BEARER_AUTH}, which any authenticated caller may read;
 * {@link PublicCorpusApi} declares it with a public one.
 */
@ApiDocs(access = ApiDocs.Access.PROTECTED, securityScheme = CorpusDocuments.BEARER_AUTH)
@OpenAPIDefinition(info = @Info(title = "Conformance corpus", version = CorpusDocuments.VERSION))
@RestApplication(name = CorpusDocuments.CORPUS, path = CorpusDocuments.CORPUS_PATH, resources = CorpusResource.class)
public interface ProtectedCorpusApi {}
