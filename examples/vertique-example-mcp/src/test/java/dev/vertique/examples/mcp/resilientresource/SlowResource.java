// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.mcp.resilientresource;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import jakarta.inject.Inject;

/** Implementor of {@link SlowEndpoint}: completes its answer five seconds after the call. */
public class SlowResource implements SlowEndpoint {

    private final Vertx vertx;

    /**
     * Creates the resource.
     *
     * @param vertx the Vert.x instance that schedules the late answer
     */
    @Inject
    public SlowResource(Vertx vertx) {
        this.vertx = vertx;
    }

    @Override
    public Future<String> slow() {
        Promise<String> late = Promise.promise();
        vertx.setTimer(5_000, id -> late.complete("late"));
        return late.future();
    }
}
