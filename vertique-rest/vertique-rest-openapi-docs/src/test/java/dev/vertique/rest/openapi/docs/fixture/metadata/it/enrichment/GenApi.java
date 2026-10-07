// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.metadata.it.enrichment;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.security.authz.AccessPolicy;
import jakarta.annotation.security.PermitAll;

/**
 * The documented application {@code gen} at {@code /gen}, listing the catalog twin the generated
 * descriptor path describes. Its document is public; its {@code info} comes from configuration.
 */
@ApiDocs(policy = GenApi.PublicDocsPolicy.class)
@RestApplication(name = GenApi.NAME, path = GenApi.PATH, resources = GeneratedCatalogResource.class)
public interface GenApi {

    /** The application's name, which also names its document. */
    String NAME = "gen";

    /** The application's path. */
    String PATH = "/gen";

    /** Anyone may read the document. */
    @PermitAll
    public interface PublicDocsPolicy extends AccessPolicy {}
}
