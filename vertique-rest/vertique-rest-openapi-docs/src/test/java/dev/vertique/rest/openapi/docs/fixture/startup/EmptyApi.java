// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.startup;

import dev.vertique.rest.jaxrs.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;

/**
 * The documented application {@code empty} at {@code /api/empty} with discovery membership and no
 * contributed resource, so its mount holds no operation. A discovery application must be the sole
 * declared registration.
 */
@ApiDocs(access = ApiDocs.Access.PUBLIC)
@RestApplication(name = EmptyApi.NAME, path = EmptyApi.PATH, discover = true)
public interface EmptyApi {

    /** The application's name, which also names its document. */
    String NAME = "empty";

    /** The application's path. */
    String PATH = "/api/empty";

    /** The application's mount path, as its registration and publication report it. */
    String MOUNT_PATH = PATH + "/*";
}
