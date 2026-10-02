// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.input.patterns;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;

/**
 * The documented application {@code patterns} at {@code /api/patterns}, listing {@link
 * PatternsResource}. Its document is public; its {@code info} comes from configuration.
 */
@ApiDocs(access = ApiDocs.Access.PUBLIC)
@RestApplication(name = PatternsApi.NAME, path = PatternsApi.PATH, resources = PatternsResource.class)
public interface PatternsApi {

    /** The application's name, which also names its document. */
    String NAME = "patterns";

    /** The application's path. */
    String PATH = "/api/patterns";
}
