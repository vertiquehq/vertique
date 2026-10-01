// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.input.it;

import dev.vertique.rest.jaxrs.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;

/**
 * The documented application {@code frozen} at {@code /api/frozen}, listing {@link FrozenResource}.
 * Its document is public; its {@code info} comes from configuration.
 */
@ApiDocs(access = ApiDocs.Access.PUBLIC)
@RestApplication(name = FrozenApi.NAME, path = FrozenApi.PATH, resources = FrozenResource.class)
public interface FrozenApi {

    /** The application's name, which also names its document. */
    String NAME = "frozen";

    /** The application's path. */
    String PATH = "/api/frozen";
}
