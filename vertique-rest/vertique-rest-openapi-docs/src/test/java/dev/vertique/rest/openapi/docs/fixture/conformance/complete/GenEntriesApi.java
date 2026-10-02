// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.conformance.complete;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;

/**
 * The documented application {@value #NAME} at {@value #PATH}, listing {@link
 * GeneratedEntryResource}, the twin its generated-shape
 * companion describes. Its document is public and carries the same {@code info} as
 * the other twin's declaration.
 */
@ApiDocs(access = ApiDocs.Access.PUBLIC)
@OpenAPIDefinition(
        info =
                @Info(
                        title = CompleteEntries.TITLE,
                        version = CompleteEntries.VERSION,
                        description = CompleteEntries.DESCRIPTION))
@RestApplication(name = GenEntriesApi.NAME, path = GenEntriesApi.PATH, resources = GeneratedEntryResource.class)
public interface GenEntriesApi {

    /** The application's name, which also names its document. */
    String NAME = "gen";

    /** The application's path. */
    String PATH = "/gen";
}
