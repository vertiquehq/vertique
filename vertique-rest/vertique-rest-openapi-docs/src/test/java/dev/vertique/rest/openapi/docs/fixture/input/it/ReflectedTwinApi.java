// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.input.it;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;

/**
 * The documented application {@code reflected} at {@code /api/reflected}, listing the twin resource
 * the reflective scanner describes. Its document is public; its {@code info} comes from
 * configuration.
 */
@ApiDocs(access = ApiDocs.Access.PUBLIC)
@RestApplication(name = ReflectedTwinApi.NAME, path = ReflectedTwinApi.PATH, resources = ReflectedSearchResource.class)
public interface ReflectedTwinApi {

    /** The application's name, which also names its document. */
    String NAME = "reflected";

    /** The application's path. */
    String PATH = "/api/reflected";
}
