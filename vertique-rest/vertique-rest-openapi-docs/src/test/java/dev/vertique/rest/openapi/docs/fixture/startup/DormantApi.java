// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.startup;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.security.authz.AccessPolicy;
import jakarta.annotation.security.PermitAll;

/**
 * The documented application {@code dormant} at {@code /api/dormant} listing {@link DormantResource},
 * whose registration is inactive: it declares a public document, but never mounts or publishes.
 */
@ApiDocs(policy = DormantApi.PublicDocsPolicy.class)
@RestApplication(name = DormantApi.NAME, path = DormantApi.PATH, resources = DormantResource.class)
public interface DormantApi {

    /** The application's name, which also names its document. */
    String NAME = "dormant";

    /** The application's path. */
    String PATH = "/api/dormant";

    /** Anyone may read the document. */
    @PermitAll
    public interface PublicDocsPolicy extends AccessPolicy {}
}
