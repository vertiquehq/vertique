// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.protecteddocs.caching;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.security.authz.AccessPolicy;
import dev.vertique.security.authz.Authorized;

/**
 * The application {@code undescribed}, whose document is protected by a scheme whose handler describes
 * nothing, so the scheme kind is unknown. No operation of it requires that scheme.
 */
@ApiDocs(policy = UndescribedKindApi.AuthenticatedDocsPolicy.class, securityScheme = CachingSchemes.UNDESCRIBED)
@RestApplication(
        name = UndescribedKindApi.NAME,
        path = UndescribedKindApi.PATH,
        resources = CachingResources.Undescribed.class)
public interface UndescribedKindApi {

    /** The application's name, which also names its document. */
    String NAME = "undescribed";

    /** The application's path. */
    String PATH = "/kinds/undescribed";

    /** Any authenticated reader may read the document. */
    @Authorized
    public interface AuthenticatedDocsPolicy extends AccessPolicy {}
}
