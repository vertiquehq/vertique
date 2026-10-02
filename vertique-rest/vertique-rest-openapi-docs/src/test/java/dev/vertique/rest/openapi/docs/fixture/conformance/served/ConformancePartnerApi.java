// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.conformance.served;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.rest.openapi.docs.fixture.contract.PartnerOrderResource;

/**
 * The documented application {@value #NAME} at {@value #PATH}, listing {@link PartnerOrderResource},
 * whose declaring interface names its own OpenAPI contract {@value #OPENAPI_PATH}, so its document is
 * that contract. It carries no {@code @OpenAPIDefinition}; no configuration gives it an {@code info}
 * or a server URL.
 */
@ApiDocs(access = ApiDocs.Access.PUBLIC)
@RestApplication(
        name = ConformancePartnerApi.NAME,
        path = ConformancePartnerApi.PATH,
        resources = PartnerOrderResource.class,
        openapiPath = ConformancePartnerApi.OPENAPI_PATH)
public interface ConformancePartnerApi {

    /** The application's name, which also names its document. */
    String NAME = "partner";

    /** The application's path. */
    String PATH = "/api/partner";

    /** The application's mount path, as the document store's log lines name it. */
    String MOUNT_PATH = PATH + "/*";

    /** The application's own contract location, a test classpath resource (OpenAPI 3.1.0, YAML). */
    String OPENAPI_PATH = "contracts/conformance-partner-openapi.yaml";
}
