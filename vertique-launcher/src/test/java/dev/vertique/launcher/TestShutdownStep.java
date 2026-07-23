// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.launcher;

import dev.vertique.core.lifecycle.ApplicationShutdownStep;
import dev.vertique.core.lifecycle.LifecyclePhase;
import io.vertx.core.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A CONFIGURE-phase shutdown step whose {@link #stop()} sets a shared marker, used by
 * {@link VertiqueBootstrapVerticleIT} to prove that undeploying the bootstrap verticle drove the
 * handle's reverse-order teardown.
 */
final class TestShutdownStep implements ApplicationShutdownStep {

    /** Set to {@code true} when {@link #stop()} runs; the IT asserts on it and resets it per test. */
    static final AtomicBoolean STOPPED = new AtomicBoolean(false);

    @Override
    public LifecyclePhase phase() {
        return LifecyclePhase.CONFIGURE;
    }

    @Override
    public Future<Void> stop() {
        STOPPED.set(true);
        return Future.succeededFuture();
    }
}
