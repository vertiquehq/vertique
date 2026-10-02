// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.conformance.served;

import dev.vertique.rest.jaxrs.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.rest.openapi.docs.fixture.contract.OrderResource;

/**
 * The documented application {@value #NAME} at {@value #PATH}, listing {@link OrderResource}. Its
 * declaring interface names no contract and carries no {@code @OpenAPIDefinition}: its own contract
 * comes only from configuration, {@code jaxrs.applications.orders.openapiPath} set to {@value
 * #CONFIGURED_OPENAPI_PATH}.
 */
@ApiDocs(access = ApiDocs.Access.PUBLIC)
@RestApplication(name = ConformanceOrdersApi.NAME, path = ConformanceOrdersApi.PATH, resources = OrderResource.class)
public interface ConformanceOrdersApi {

    /** The application's name, which also names its document. */
    String NAME = "orders";

    /** The application's path. */
    String PATH = "/api/orders";

    /** The application's mount path, as the document store's log lines name it. */
    String MOUNT_PATH = PATH + "/*";

    /** The contract location the configuration names, a test classpath resource (OpenAPI 3.0.3, JSON). */
    String CONFIGURED_OPENAPI_PATH = "contracts/conformance-orders-openapi.json";
}
