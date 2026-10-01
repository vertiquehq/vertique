// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.startup.warning;

import dev.vertique.rest.core.router.MountCustomizer;
import dev.vertique.rest.core.router.MountMeta;
import io.vertx.ext.web.Router;

/**
 * A mount customizer that matches only the mount at {@value #MOUNT_PATH}, so it never matches the
 * documentation mount. It adds nothing to the routers it is given.
 */
public final class MgmtOnlyCustomizer implements MountCustomizer {

    /** The only mount path this customizer matches. */
    public static final String MOUNT_PATH = "/api/mgmt/*";

    /** Creates the customizer. */
    public MgmtOnlyCustomizer() {}

    @Override
    public boolean matches(MountMeta meta) {
        return MOUNT_PATH.equals(meta.mountPath());
    }

    @Override
    public void customize(Router mountRouter, MountMeta meta) {
        // Adds nothing: the fixture exists to be matched, not to change routing.
    }
}
