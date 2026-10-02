// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.metadata.it.enrichment;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;

/**
 * The documented application {@code shop} at {@code /shop}, listing {@link ProductsResource}. Its
 * document is public; its {@code info} comes from configuration.
 */
@ApiDocs(access = ApiDocs.Access.PUBLIC)
@RestApplication(name = ShopApi.NAME, path = ShopApi.PATH, resources = ProductsResource.class)
public interface ShopApi {

    /** The application's name, which also names its document. */
    String NAME = "shop";

    /** The application's path. */
    String PATH = "/shop";
}
