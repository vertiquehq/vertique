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
 * Application {@code ops} at {@code /api/ops} listing {@link OpsResource}, whose policy is public and also lists allowed roles, a mixture the policy resolver rejects. The annotation processor refuses this shape too; only a hand-written registration declares it.
 */
@ApiDocs(policy = OpsPublicWithRolesApi.PublicWithRolesDocsPolicy.class)
@RestApplication(name = OpsApi.NAME, path = OpsApi.PATH, resources = OpsResource.class)
public interface OpsPublicWithRolesApi {
    /** A malformed policy: it is public and also lists roles. */
    @PermitAll
    @RolesAllowed({"admin"})
    public interface PublicWithRolesDocsPolicy extends AccessPolicy {}
}
