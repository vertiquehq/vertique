// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.metadata.it.hidden;

import dev.vertique.rest.jaxrs.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;

/**
 * The protected twin of {@link HiddenOperationsApi}: the same application name, path, and resources,
 * with a protected document. A component registers this interface or {@link HiddenOperationsApi},
 * never both.
 */
@ApiDocs(access = ApiDocs.Access.PROTECTED, securityScheme = ProtectedHiddenOperationsApi.SECURITY_SCHEME)
@RestApplication(
        name = HiddenOperationsApi.NAME,
        path = HiddenOperationsApi.PATH,
        resources = {
            MixedOperationsResource.class,
            HiddenClassResource.class,
            HiddenContractResource.class,
            PartlyHiddenContractResource.class
        })
public interface ProtectedHiddenOperationsApi {

    /** The security scheme that would guard the document routes. */
    String SECURITY_SCHEME = "bearerAuth";
}
