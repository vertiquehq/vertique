// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.disclosure.it.hidden;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.security.authz.AccessPolicy;
import dev.vertique.security.authz.Authorized;

/**
 * The protected twin of {@link HiddenProbeApi}: the same application name, path, and resource, with
 * a protected document. A component registers this interface or {@link HiddenProbeApi}, never both.
 */
@ApiDocs(
        policy = ProtectedHiddenProbeApi.AuthenticatedDocsPolicy.class,
        securityScheme = ProtectedHiddenProbeApi.SECURITY_SCHEME)
@RestApplication(name = HiddenProbeApi.NAME, path = HiddenProbeApi.PATH, resources = HiddenProbeResource.class)
public interface ProtectedHiddenProbeApi {

    /** The security scheme that would guard the document routes. */
    String SECURITY_SCHEME = "bearerAuth";

    /** Any authenticated reader may read the document. */
    @Authorized
    public interface AuthenticatedDocsPolicy extends AccessPolicy {}
}
