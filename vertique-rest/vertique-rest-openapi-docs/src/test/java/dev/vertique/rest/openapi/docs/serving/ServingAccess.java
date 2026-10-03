// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.serving;

import io.vertx.ext.web.Router;

/**
 * Test access to package-private parts of the documentation mount for tests that live outside this
 * package. Each method delegates to the production member; nothing is reimplemented here.
 */
public final class ServingAccess {

    private ServingAccess() {}

    /**
     * Reports whether the composition validator has checked a documentation mount.
     *
     * @param mount the documentation mount
     * @return {@code true} once the validator has marked the mount
     */
    public static boolean isValidated(DocsRouterMount mount) {
        return mount.isValidated();
    }

    /**
     * Registers a documentation mount's routes on a router, as the mount's own build does.
     *
     * @param mount the documentation mount
     * @param router the router to register the routes on
     */
    public static void buildInto(DocsRouterMount mount, Router router) {
        mount.buildInto(router);
    }
}
