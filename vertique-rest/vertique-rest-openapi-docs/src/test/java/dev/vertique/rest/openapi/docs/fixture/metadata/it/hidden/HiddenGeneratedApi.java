// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.metadata.it.hidden;

import dev.vertique.rest.jaxrs.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;

/**
 * The documented application {@code hiddengen} at {@code /api/hiddengen}, listing the
 * generated-path twins of the hidden resource class and of the resource implementing a hidden
 * interface; both are described by hand-written {@code _JaxRsDescriptor} companions. Every
 * operation it lists is hidden. Its document is public; its {@code info} comes from configuration.
 */
@ApiDocs(access = ApiDocs.Access.PUBLIC)
@RestApplication(
        name = HiddenGeneratedApi.NAME,
        path = HiddenGeneratedApi.PATH,
        resources = {GeneratedHiddenClassResource.class, GeneratedHiddenContractResource.class})
public interface HiddenGeneratedApi {

    /** The application's name, which also names its document. */
    String NAME = "hiddengen";

    /** The application's path. */
    String PATH = "/api/hiddengen";
}
