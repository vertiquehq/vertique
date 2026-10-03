// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.startup.warning;

import dev.vertique.rest.core.router.MountCustomizer;
import dev.vertique.rest.core.router.MountMeta;
import io.vertx.ext.web.Router;

/**
 * A mount customizer keeping the default {@code matches}, so it matches every mount, the
 * documentation mount included. It adds nothing to the routers it is given.
 */
public final class EveryMountCustomizer implements MountCustomizer {

    /** Creates the customizer. */
    public EveryMountCustomizer() {}

    @Override
    public void customize(Router mountRouter, MountMeta meta) {
        // Adds nothing: the fixture exists to be matched, not to change routing.
    }
}
