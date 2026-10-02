// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.startup.contractload;

import dev.vertique.rest.jaxrs.application.RestApplication;
import dev.vertique.rest.openapi.docs.fixture.CatalogResource;

/**
 * The undocumented application {@code annotated} at {@code /api/annotated}, listing {@link
 * CatalogResource}, whose own declaration names the contract location {@value #OPENAPI_PATH}: a test
 * resource the {@code openapi-contract} strategy cannot load, because its only server URL is relative.
 * Its registration is hand-written in {@link ContractLoadModules.Annotated}.
 */
@RestApplication(
        name = AnnotatedContractApi.NAME,
        path = AnnotatedContractApi.PATH,
        resources = CatalogResource.class,
        openapiPath = AnnotatedContractApi.OPENAPI_PATH)
public interface AnnotatedContractApi {

    /** The application's name. */
    String NAME = "annotated";

    /** The application's path. */
    String PATH = "/api/annotated";

    /** The declared contract location, a test classpath resource with a relative server URL. */
    String OPENAPI_PATH = ContractLoadModules.RELATIVE_SERVERS_RESOURCE;
}
