// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture;

import dev.vertique.rest.jaxrs.application.RestApplication;

/** The undocumented application {@code mgmt} at {@code /api/mgmt}: its declaring interface carries no {@code @ApiDocs}. */
@RestApplication(name = MgmtApi.NAME, path = MgmtApi.PATH, resources = ManagementResource.class)
public interface MgmtApi {

    /** The application's name. */
    String NAME = "mgmt";

    /** The application's path. */
    String PATH = "/api/mgmt";

    /** The application's mount path, as its registration and publication report it. */
    String MOUNT_PATH = PATH + "/*";
}
