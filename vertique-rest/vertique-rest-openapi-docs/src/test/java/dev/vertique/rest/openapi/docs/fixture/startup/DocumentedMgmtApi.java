// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.startup;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.rest.openapi.docs.fixture.ManagementResource;
import dev.vertique.rest.openapi.docs.fixture.MgmtApi;
import dev.vertique.security.authz.AccessPolicy;
import jakarta.annotation.security.PermitAll;

/**
 * The same declaration as {@link MgmtApi} with a public document: application {@code mgmt} at
 * {@code /api/mgmt} listing {@link ManagementResource}, whose declaring interface carries
 * {@code @ApiDocs(policy = PublicDocsPolicy.class)}.
 */
@ApiDocs(policy = DocumentedMgmtApi.PublicDocsPolicy.class)
@RestApplication(name = MgmtApi.NAME, path = MgmtApi.PATH, resources = ManagementResource.class)
public interface DocumentedMgmtApi {
    /** Anyone may read the document. */
    @PermitAll
    public interface PublicDocsPolicy extends AccessPolicy {}
}
