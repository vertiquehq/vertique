// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.input.it;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.security.authz.AccessPolicy;
import jakarta.annotation.security.PermitAll;

/**
 * The documented application {@code verbatim} at {@code /api/verbatim}, listing {@link
 * VerbatimResource}. Its document is public; its {@code info} comes from configuration.
 */
@ApiDocs(policy = VerbatimApi.PublicDocsPolicy.class)
@RestApplication(name = VerbatimApi.NAME, path = VerbatimApi.PATH, resources = VerbatimResource.class)
public interface VerbatimApi {

    /** The application's name, which also names its document. */
    String NAME = "verbatim";

    /** The application's path. */
    String PATH = "/api/verbatim";

    /** Anyone may read the document. */
    @PermitAll
    public interface PublicDocsPolicy extends AccessPolicy {}
}
