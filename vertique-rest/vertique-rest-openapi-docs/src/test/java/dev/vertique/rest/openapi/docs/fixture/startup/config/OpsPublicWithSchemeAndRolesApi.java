// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.startup.config;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.rest.openapi.docs.fixture.startup.OpsApi;
import dev.vertique.rest.openapi.docs.fixture.startup.OpsResource;
import dev.vertique.security.authz.AccessPolicy;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;

/**
 * Application {@code ops} at {@code /api/ops} listing {@link OpsResource}, whose document
 * both names a security scheme and uses a policy that is public and also lists an allowed role: a
 * public document with a scheme, and a malformed policy. Both values
 * carry the marker {@code zq7}, which no failure may echo. The annotation processor refuses this
 * shape; only a hand-written registration declares it.
 */
@ApiDocs(policy = OpsPublicWithSchemeAndRolesApi.PublicWithRolesDocsPolicy.class, securityScheme = "zq7Auth")
@RestApplication(name = OpsApi.NAME, path = OpsApi.PATH, resources = OpsResource.class)
public interface OpsPublicWithSchemeAndRolesApi {
    /** A malformed policy: it is public and also lists roles. */
    @PermitAll
    @RolesAllowed({"zq7Role"})
    public interface PublicWithRolesDocsPolicy extends AccessPolicy {}
}
