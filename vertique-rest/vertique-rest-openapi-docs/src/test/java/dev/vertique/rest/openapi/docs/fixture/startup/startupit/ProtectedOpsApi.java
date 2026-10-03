// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.startup.startupit;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.rest.openapi.docs.fixture.startup.OpsApi;
import dev.vertique.rest.openapi.docs.fixture.startup.OpsResource;

/**
 * Application {@code ops} at {@code /api/ops} listing {@link OpsResource}, whose protected document
 * is guarded by the {@value #SECURITY_SCHEME} scheme. Used only in refused-startup proofs beside
 * another protected document, so that several {@code @ApiDocs} value violations occur at once.
 */
@ApiDocs(access = ApiDocs.Access.PROTECTED, securityScheme = ProtectedOpsApi.SECURITY_SCHEME)
@RestApplication(name = OpsApi.NAME, path = OpsApi.PATH, resources = OpsResource.class)
public interface ProtectedOpsApi {

    /** The security scheme guarding the document routes. */
    String SECURITY_SCHEME = "otherAuth";
}
