// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.conformance.corpus;

import dev.vertique.rest.jaxrs.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.rest.openapi.docs.fixture.responses.it.AccountResource;
import dev.vertique.rest.openapi.docs.fixture.responses.it.FixedReceiptResource;
import dev.vertique.rest.openapi.docs.fixture.responses.it.HiddenOperationResource;
import dev.vertique.rest.openapi.docs.fixture.responses.it.ReportResource;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;

/**
 * Declares the responses application {@value CorpusDocuments#RESPONSES} with a public document;
 * {@link ProtectedResponsesApi} declares it with a protected one.
 */
@ApiDocs(access = ApiDocs.Access.PUBLIC)
@OpenAPIDefinition(info = @Info(title = "Conformance responses", version = CorpusDocuments.VERSION))
@RestApplication(
        name = CorpusDocuments.RESPONSES,
        path = CorpusDocuments.RESPONSES_PATH,
        resources = {
            AccountResource.class,
            ReportResource.class,
            FixedReceiptResource.class,
            HiddenOperationResource.class
        })
public interface PublicResponsesApi {}
