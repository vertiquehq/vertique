// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.security.orders;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.security.authz.AccessPolicy;
import jakarta.annotation.security.PermitAll;

/**
 * The documented application {@code orders} at {@code /api} (mount {@code /api/*}), listing {@link
 * OrderResource}, whose operations declare every supported security shape. Its document is public;
 * its {@code info} comes from configuration.
 */
@ApiDocs(policy = OrdersApi.PublicDocsPolicy.class)
@RestApplication(name = OrdersApi.NAME, path = OrdersApi.PATH, resources = OrderResource.class)
public interface OrdersApi {

    /** The application's name, which also names its document. */
    String NAME = "orders";

    /** The application's path. */
    String PATH = "/api";

    /** Anyone may read the document. */
    @PermitAll
    public interface PublicDocsPolicy extends AccessPolicy {}
}
