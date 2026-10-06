// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.startup.startupit;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.rest.openapi.docs.fixture.startup.OpsApi;
import dev.vertique.rest.openapi.docs.fixture.startup.OpsResource;
import dev.vertique.security.authz.AccessPolicy;
import dev.vertique.security.authz.Authorized;

/**
 * Application {@code ops} at {@code /api/ops} listing {@link OpsResource}, whose protected document
 * is guarded by the {@value #SECURITY_SCHEME} scheme. Used only in refused-startup proofs beside
 * another protected document, so that several {@code @ApiDocs} value violations occur at once.
 */
@ApiDocs(policy = ProtectedOpsApi.AuthenticatedDocsPolicy.class, securityScheme = ProtectedOpsApi.SECURITY_SCHEME)
@RestApplication(name = OpsApi.NAME, path = OpsApi.PATH, resources = OpsResource.class)
public interface ProtectedOpsApi {

    /** The security scheme guarding the document routes. */
    String SECURITY_SCHEME = "otherAuth";

    /** Any authenticated reader may read the document. */
    @Authorized
    public interface AuthenticatedDocsPolicy extends AccessPolicy {}
}
