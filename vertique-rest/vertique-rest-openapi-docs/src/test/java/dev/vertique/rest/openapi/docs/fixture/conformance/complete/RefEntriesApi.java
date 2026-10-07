// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.conformance.complete;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.security.authz.AccessPolicy;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import jakarta.annotation.security.PermitAll;

/**
 * The documented application {@value #NAME} at {@value #PATH}, listing {@link
 * ReflectedEntryResource}, the twin the reflective scanner
 * describes. Its document is public and carries the same {@code info} as
 * the other twin's declaration.
 */
@ApiDocs(policy = RefEntriesApi.PublicDocsPolicy.class)
@OpenAPIDefinition(
        info =
                @Info(
                        title = CompleteEntries.TITLE,
                        version = CompleteEntries.VERSION,
                        description = CompleteEntries.DESCRIPTION))
@RestApplication(name = RefEntriesApi.NAME, path = RefEntriesApi.PATH, resources = ReflectedEntryResource.class)
public interface RefEntriesApi {

    /** The application's name, which also names its document. */
    String NAME = "ref";

    /** The application's path. */
    String PATH = "/ref";

    /** Anyone may read the document. */
    @PermitAll
    public interface PublicDocsPolicy extends AccessPolicy {}
}
