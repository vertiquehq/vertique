// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.security.unit;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.security.authz.AccessPolicy;
import jakarta.annotation.security.PermitAll;

/**
 * The documented application {@code ghost} at {@code /api/ghost}, whose only resource, {@link
 * GhostResource}, requires the security scheme {@value GhostResource#SCHEME}. Registered by hand
 * through {@link GhostRegistrationModule}; the annotation processor never sees it.
 */
@ApiDocs(policy = GhostApi.PublicDocsPolicy.class)
@RestApplication(name = GhostApi.NAME, path = GhostApi.PATH, resources = GhostResource.class)
public interface GhostApi {

    /** The application's name, which also names its document. */
    String NAME = "ghost";

    /** The application's path. */
    String PATH = "/api/ghost";

    /** The application's mount path, as its registration and publication report it. */
    String MOUNT_PATH = PATH + "/*";

    /** Anyone may read the document. */
    @PermitAll
    public interface PublicDocsPolicy extends AccessPolicy {}
}
