// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.startup;

import dev.vertique.rest.jaxrs.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;

/**
 * The documented root application {@code api} at {@code /} with discovery membership: it holds
 * every contributed resource. A discovery application must be the sole declared registration.
 */
@ApiDocs(access = ApiDocs.Access.PUBLIC)
@RestApplication(name = RootApi.NAME, path = RootApi.PATH, discover = true)
public interface RootApi {

    /** The application's name, which also names its document. */
    String NAME = "api";

    /** The application's path. */
    String PATH = "/";

    /** The application's mount path, as its registration and publication report it. */
    String MOUNT_PATH = "/*";
}
