// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.startup.collision;

import dev.vertique.rest.core.application.RestApplication;

/**
 * The undocumented application {@code api} at {@code /api}, holding {@link DocsNameResource}. It
 * carries no {@code @ApiDocs}, so it has no document of its own.
 */
@RestApplication(name = ApiApp.NAME, path = ApiApp.PATH, resources = DocsNameResource.class)
public interface ApiApp {

    /** The application's name. */
    String NAME = "api";

    /** The application's path. */
    String PATH = "/api";
}
