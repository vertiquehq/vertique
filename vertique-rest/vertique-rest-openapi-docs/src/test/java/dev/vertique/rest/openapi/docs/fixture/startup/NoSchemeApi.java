// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.startup;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.rest.openapi.docs.fixture.ManagementResource;
import dev.vertique.rest.openapi.docs.fixture.MgmtApi;
import dev.vertique.security.authz.AccessPolicy;
import dev.vertique.security.authz.Authorized;

/**
 * The same declaration as {@link MgmtApi} with a protected document that names no security scheme:
 * application {@code mgmt} at {@code /api/mgmt} listing {@link ManagementResource}. The annotation
 * processor refuses this shape, so only a hand-written registration can carry it.
 */
@ApiDocs(policy = NoSchemeApi.AuthenticatedDocsPolicy.class)
@RestApplication(name = MgmtApi.NAME, path = MgmtApi.PATH, resources = ManagementResource.class)
public interface NoSchemeApi {
    /** Any authenticated reader may read the document. */
    @Authorized
    public interface AuthenticatedDocsPolicy extends AccessPolicy {}
}
