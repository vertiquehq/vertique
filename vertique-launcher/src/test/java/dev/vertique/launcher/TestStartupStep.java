// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.launcher;

import dev.vertique.core.lifecycle.ApplicationStartupStep;
import dev.vertique.core.lifecycle.LifecyclePhase;
import io.vertx.core.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A CONFIGURE-phase startup step whose {@link #start()} sets a shared marker, used by
 * {@link VertiqueBootstrapVerticleIT} to prove the lifecycle runner actually ran the application's
 * startup work after the bootstrap verticle deployed.
 */
final class TestStartupStep implements ApplicationStartupStep {

    /** Set to {@code true} when {@link #start()} runs; the IT asserts on it and resets it per test. */
    static final AtomicBoolean STARTED = new AtomicBoolean(false);

    @Override
    public LifecyclePhase phase() {
        return LifecyclePhase.CONFIGURE;
    }

    @Override
    public Future<Void> start() {
        STARTED.set(true);
        return Future.succeededFuture();
    }
}
