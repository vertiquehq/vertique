// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.startup;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.security.authz.AccessPolicy;
import jakarta.annotation.security.PermitAll;

/**
 * The documented application {@code ops} at {@code /api/ops} listing {@link OpsResource}: its
 * declaring interface carries {@code @ApiDocs(policy = PublicDocsPolicy.class)}.
 */
@ApiDocs(policy = OpsApi.PublicDocsPolicy.class)
@RestApplication(name = OpsApi.NAME, path = OpsApi.PATH, resources = OpsResource.class)
public interface OpsApi {

    /** The application's name, which also names its document. */
    String NAME = "ops";

    /** The application's path. */
    String PATH = "/api/ops";

    /** The application's mount path, as its registration and publication report it. */
    String MOUNT_PATH = PATH + "/*";

    /** Anyone may read the document. */
    @PermitAll
    public interface PublicDocsPolicy extends AccessPolicy {}
}
