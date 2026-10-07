// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.startup.config;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.rest.openapi.docs.fixture.startup.OpsApi;
import dev.vertique.rest.openapi.docs.fixture.startup.OpsResource;
import dev.vertique.security.authz.AccessPolicy;
import jakarta.annotation.security.RolesAllowed;

/**
 * Application {@code ops} at {@code /api/ops} listing {@link OpsResource}, whose roles policy lists an empty allowed role, which the policy resolver rejects. The annotation processor refuses this shape; only a hand-written registration declares it.
 */
@ApiDocs(policy = OpsEmptyRoleEntryApi.RolesDocsPolicy.class, securityScheme = "bearerAuth")
@RestApplication(name = OpsApi.NAME, path = OpsApi.PATH, resources = OpsResource.class)
public interface OpsEmptyRoleEntryApi {
    /** Readers holding one of the listed roles may read the document. */
    @RolesAllowed({""})
    public interface RolesDocsPolicy extends AccessPolicy {}
}
