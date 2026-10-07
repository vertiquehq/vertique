// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.metadata.it.enrichment;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.security.authz.AccessPolicy;
import jakarta.annotation.security.PermitAll;

/**
 * The documented application {@code refl} at {@code /refl}, listing the catalog twin the reflective
 * scanner describes. Its document is public; its {@code info} comes from configuration.
 */
@ApiDocs(policy = ReflApi.PublicDocsPolicy.class)
@RestApplication(name = ReflApi.NAME, path = ReflApi.PATH, resources = ReflectedCatalogResource.class)
public interface ReflApi {

    /** The application's name, which also names its document. */
    String NAME = "refl";

    /** The application's path. */
    String PATH = "/refl";

    /** Anyone may read the document. */
    @PermitAll
    public interface PublicDocsPolicy extends AccessPolicy {}
}
