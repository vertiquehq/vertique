// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.disclosure.it.redaction;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.security.authz.AccessPolicy;
import dev.vertique.security.authz.Authorized;

/**
 * The same declaration as {@link FoldsApi} with a protected document guarded by the {@value
 * #SECURITY_SCHEME} scheme. Only a component that renders documents without serving them lists it.
 */
@ApiDocs(policy = ProtectedFoldsApi.AuthenticatedDocsPolicy.class, securityScheme = ProtectedFoldsApi.SECURITY_SCHEME)
@RestApplication(name = FoldsApi.NAME, path = FoldsApi.PATH, resources = FoldsResource.class)
public interface ProtectedFoldsApi {

    /** The security scheme guarding the document. */
    String SECURITY_SCHEME = "bearerAuth";

    /** Any authenticated reader may read the document. */
    @Authorized
    public interface AuthenticatedDocsPolicy extends AccessPolicy {}
}
