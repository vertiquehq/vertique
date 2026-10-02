// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.startup.contract;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.rest.openapi.docs.fixture.CatalogResource;

/**
 * The documented application {@code public} at {@code /api/public}, like {@code PublicApi}, but
 * declaring its own OpenAPI contract location {@value #OPENAPI_PATH}, a test resource that describes
 * exactly its routed operations. Its registration is hand-written in {@link ContractRegistrations}.
 */
@ApiDocs(access = ApiDocs.Access.PUBLIC)
@RestApplication(
        name = OwnContractPublicApi.NAME,
        path = OwnContractPublicApi.PATH,
        resources = CatalogResource.class,
        openapiPath = OwnContractPublicApi.OPENAPI_PATH)
public interface OwnContractPublicApi {

    /** The application's name, which also names its document. */
    String NAME = "public";

    /** The application's path. */
    String PATH = "/api/public";

    /**
     * The application's own contract location, a test classpath resource without a {@code servers}
     * member, because the {@code openapi-contract} strategy accepts only absolute server URLs or none.
     */
    String OPENAPI_PATH = "public-contract.json";
}
