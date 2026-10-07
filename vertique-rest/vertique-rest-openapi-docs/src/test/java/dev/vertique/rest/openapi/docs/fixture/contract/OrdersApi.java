// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.contract;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.security.authz.AccessPolicy;
import jakarta.annotation.security.PermitAll;

/**
 * The documented application {@value #NAME} at {@value #PATH}, listing {@link OrderResource}. Its
 * declaring interface names no contract and carries no {@code @OpenAPIDefinition}: its own contract
 * comes only from configuration ({@code jaxrs.applications.orders.openapiPath}).
 */
@ApiDocs(policy = OrdersApi.PublicDocsPolicy.class)
@RestApplication(name = OrdersApi.NAME, path = OrdersApi.PATH, resources = OrderResource.class)
public interface OrdersApi {

    /** The application's name, which also names its document. */
    String NAME = "orders";

    /** The application's path. */
    String PATH = "/api/orders";

    /** The application's mount path, as its registration and publication report it. */
    String MOUNT_PATH = PATH + "/*";

    /** Anyone may read the document. */
    @PermitAll
    public interface PublicDocsPolicy extends AccessPolicy {}
}
