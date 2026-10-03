// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.input.it;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;

/**
 * The documented application {@code generated} at {@code /api/generated}, listing the twin resource
 * the generated descriptor path describes. Its document is public; its {@code info} comes from
 * configuration.
 */
@ApiDocs(access = ApiDocs.Access.PUBLIC)
@RestApplication(name = GeneratedTwinApi.NAME, path = GeneratedTwinApi.PATH, resources = GeneratedSearchResource.class)
public interface GeneratedTwinApi {

    /** The application's name, which also names its document. */
    String NAME = "generated";

    /** The application's path. */
    String PATH = "/api/generated";
}
