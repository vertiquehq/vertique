// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.security.vault;

import dev.vertique.rest.jaxrs.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;

/**
 * The documented application {@code vault} at {@value #PATH} (mount {@value #MOUNT}), listing {@link
 * VaultResource}, whose one operation requires a scheme whose handler describes nothing. Its document
 * is public; its {@code info} and server URL come from configuration.
 */
@ApiDocs(access = ApiDocs.Access.PUBLIC)
@RestApplication(name = VaultApi.NAME, path = VaultApi.PATH, resources = VaultResource.class)
public interface VaultApi {

    /** The application's name, which also names its document. */
    String NAME = "vault";

    /** The application's path. */
    String PATH = "/vault";

    /** The application's mount path, as registered. */
    String MOUNT = "/vault/*";
}
