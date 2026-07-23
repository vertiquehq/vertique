// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.launcher;

import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import io.vertx.core.json.JsonObject;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Minimal no-op verticle used in launch integration tests.
 *
 * <p>On {@link #start()}, records the current {@link #config()} into {@link #startedConfig} and
 * completes successfully. Tests can read {@link #startedConfig} to verify the configuration
 * propagated correctly through the bootstrap chain.
 */
public final class NoopVerticle extends VerticleBase {

    /**
     * Holds the {@link JsonObject} captured from {@link #config()} on the most recent successful
     * start. Set to {@code null} initially; updated each time a {@code NoopVerticle} is deployed.
     */
    static final AtomicReference<JsonObject> startedConfig = new AtomicReference<>(null);

    /**
     * Records {@code config()} into {@link #startedConfig} and completes successfully.
     *
     * @return a succeeded future
     */
    @Override
    public Future<?> start() {
        startedConfig.set(config());
        return Future.succeededFuture();
    }
}
