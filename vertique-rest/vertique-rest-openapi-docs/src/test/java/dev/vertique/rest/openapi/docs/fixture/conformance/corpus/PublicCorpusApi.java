// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.conformance.corpus;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.rest.openapi.docs.fixture.corpus.CorpusResource;
import dev.vertique.security.authz.AccessPolicy;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import jakarta.annotation.security.PermitAll;

/**
 * Declares the request-body corpus application {@value CorpusDocuments#CORPUS} with a public
 * document; {@link ProtectedCorpusApi} declares it with a protected one.
 */
@ApiDocs(policy = PublicCorpusApi.PublicDocsPolicy.class)
@OpenAPIDefinition(info = @Info(title = "Conformance corpus", version = CorpusDocuments.VERSION))
@RestApplication(name = CorpusDocuments.CORPUS, path = CorpusDocuments.CORPUS_PATH, resources = CorpusResource.class)
public interface PublicCorpusApi {
    /** Anyone may read the document. */
    @PermitAll
    public interface PublicDocsPolicy extends AccessPolicy {}
}
