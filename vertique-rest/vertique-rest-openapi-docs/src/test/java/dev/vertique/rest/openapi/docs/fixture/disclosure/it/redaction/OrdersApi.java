// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.disclosure.it.redaction;

import dev.vertique.rest.jaxrs.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;

/**
 * The documented application {@code orders} at {@code /api/orders}, listing {@link OrdersResource}.
 * Its document is public; its {@code info} comes from configuration, which may also switch the
 * document off.
 */
@ApiDocs(access = ApiDocs.Access.PUBLIC)
@RestApplication(name = OrdersApi.NAME, path = OrdersApi.PATH, resources = OrdersResource.class)
public interface OrdersApi {

    /** The application's name, which also names its document. */
    String NAME = "orders";

    /** The application's path. */
    String PATH = "/api/orders";
}
