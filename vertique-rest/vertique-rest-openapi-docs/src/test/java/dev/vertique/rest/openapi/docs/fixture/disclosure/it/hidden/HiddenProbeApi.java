// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.disclosure.it.hidden;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.security.authz.AccessPolicy;
import jakarta.annotation.security.PermitAll;

/**
 * The documented application {@code hidden} at {@code /api/hidden}, listing {@link
 * HiddenProbeResource}. Its document is public; its {@code info} comes from configuration.
 */
@ApiDocs(policy = HiddenProbeApi.PublicDocsPolicy.class)
@RestApplication(name = HiddenProbeApi.NAME, path = HiddenProbeApi.PATH, resources = HiddenProbeResource.class)
public interface HiddenProbeApi {

    /** The application's name, which also names its document. */
    String NAME = "hidden";

    /** The application's path. */
    String PATH = "/api/hidden";

    /** Anyone may read the document. */
    @PermitAll
    public interface PublicDocsPolicy extends AccessPolicy {}
}
