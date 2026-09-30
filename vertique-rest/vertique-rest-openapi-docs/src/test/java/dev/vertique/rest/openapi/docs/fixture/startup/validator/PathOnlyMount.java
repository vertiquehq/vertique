// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.startup.validator;

import dev.vertique.rest.core.router.RouterMount;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.ext.web.Router;

/**
 * A plain, non-JAX-RS {@link RouterMount} at a given path, with the default phase, priority, order
 * key, and metadata. It is only handed to a composition validator; its router is never created.
 */
public final class PathOnlyMount implements RouterMount {

    private final String mountPath;

    /**
     * Creates the mount.
     *
     * @param mountPath the mount path, ending with {@code /*}
     */
    public PathOnlyMount(String mountPath) {
        this.mountPath = mountPath;
    }

    @Override
    public String mountPath() {
        return mountPath;
    }

    @Override
    public Future<Router> createRouter(Vertx vertx) {
        return Future.failedFuture(new UnsupportedOperationException("the path-only mount is never routed"));
    }
}
