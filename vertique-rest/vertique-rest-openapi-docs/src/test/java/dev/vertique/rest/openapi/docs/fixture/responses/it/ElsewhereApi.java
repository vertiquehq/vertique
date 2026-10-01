// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.responses.it;

import dev.vertique.rest.jaxrs.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;

/**
 * The documented application {@code elsewhere} at {@code /elsewhere} (mount {@code /elsewhere/*}),
 * listing {@link FixedReceiptResource}. Its mount does not overlap {@code /api/*}, so it can be
 * composed beside an application of this package at {@code /api}; its document publishes cleanly.
 * Its document is public; its {@code info} comes from configuration.
 */
@ApiDocs(access = ApiDocs.Access.PUBLIC)
@RestApplication(name = ElsewhereApi.NAME, path = ElsewhereApi.PATH, resources = FixedReceiptResource.class)
public interface ElsewhereApi {

    /** The application's name, which also names its document. */
    String NAME = "elsewhere";

    /** The application's path. */
    String PATH = "/elsewhere";
}
