// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.startup.warning;

import dev.vertique.rest.core.lifecycle.RouterLifecycleHook;
import io.vertx.ext.web.Router;

/** A router lifecycle hook that observes each router it is given and changes nothing. */
public final class AuditHook implements RouterLifecycleHook {

    /** Creates the hook. */
    public AuditHook() {}

    @Override
    public void afterRouterCreated(Router router) {
        // Changes nothing: the fixture exists to be listed, not to change routing.
    }
}
