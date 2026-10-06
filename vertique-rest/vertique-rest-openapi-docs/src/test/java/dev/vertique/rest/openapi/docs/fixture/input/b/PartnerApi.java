// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.input.b;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.security.authz.AccessPolicy;
import jakarta.annotation.security.PermitAll;

/**
 * The documented application {@code partner} at {@code /api/partner}, listing {@link
 * OrdersResource}. Its document is public; its {@code info} comes from configuration.
 */
@ApiDocs(policy = PartnerApi.PublicDocsPolicy.class)
@RestApplication(name = PartnerApi.NAME, path = PartnerApi.PATH, resources = OrdersResource.class)
public interface PartnerApi {

    /** The application's name, which also names its document. */
    String NAME = "partner";

    /** The application's path. */
    String PATH = "/api/partner";

    /** Anyone may read the document. */
    @PermitAll
    public interface PublicDocsPolicy extends AccessPolicy {}
}
