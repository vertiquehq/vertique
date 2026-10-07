// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.startup.config;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.rest.openapi.docs.fixture.startup.OpsResource;
import dev.vertique.security.authz.AccessPolicy;
import dev.vertique.security.authz.Authorized;

/**
 * Application {@code audit} at {@code /api/audit} listing {@link OpsResource}, whose restrictive
 * policy names no security scheme. Its name sorts before {@code ops}, so a failure that reports it
 * together with an {@code ops} violation shows the sorted order. The annotation processor refuses this
 * shape; only a hand-written registration declares it.
 */
@ApiDocs(policy = AuditProtectedWithoutSchemeApi.AuthenticatedDocsPolicy.class)
@RestApplication(
        name = AuditProtectedWithoutSchemeApi.NAME,
        path = AuditProtectedWithoutSchemeApi.PATH,
        resources = OpsResource.class)
public interface AuditProtectedWithoutSchemeApi {

    /** The application's name. */
    String NAME = "audit";

    /** The application's path. */
    String PATH = "/api/audit";

    /** Any authenticated reader may read the document. */
    @Authorized
    public interface AuthenticatedDocsPolicy extends AccessPolicy {}
}
