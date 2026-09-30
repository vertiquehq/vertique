// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.startup.validator;

import dev.vertique.rest.core.router.MountMeta;
import dev.vertique.rest.core.router.RouterMount;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.ext.web.Router;
import java.util.Set;

/**
 * A plain, non-JAX-RS {@link RouterMount} at {@value #MOUNT_PATH} whose {@link #meta()} forges the
 * application name {@code public}: a mount that merely claims an application's name must not
 * satisfy that application's document.
 *
 * <p>A composition validator never creates routers, so {@link #createRouter} fails the test.
 */
public final class ForgedMetaMount implements RouterMount {

    /** The mount's path, the path of application {@code public}. */
    public static final String MOUNT_PATH = "/api/public/*";

    /** Creates the mount. */
    public ForgedMetaMount() {}

    @Override
    public String mountPath() {
        return MOUNT_PATH;
    }

    @Override
    public MountMeta meta() {
        return new MountMeta("forged", MOUNT_PATH, null, Set.of(), "public");
    }

    @Override
    public Future<Router> createRouter(Vertx vertx) {
        throw new AssertionError("a composition validator must not create a mount's router");
    }
}
