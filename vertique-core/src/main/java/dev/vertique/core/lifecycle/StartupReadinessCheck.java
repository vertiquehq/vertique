// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.lifecycle;

import dev.vertique.core.health.HealthCheck;
import dev.vertique.core.health.HealthCheckResult;
import io.vertx.core.Future;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Readiness check that stays {@code DOWN} until the lifecycle runner has finished every phase, so a
 * readiness probe never reports an application ready before its verticles, and so its API listeners, are
 * running.
 *
 * <p>The same singleton is both the {@code @Readiness} check named {@value #NAME} and a startup step in
 * {@link LifecyclePhase#AFTER_START}, the phase that runs after the verticle phases. Its priority is the
 * highest, so it runs after every other {@code AFTER_START} step. {@link CoreLifecycleStepsModule}
 * contributes both. Once {@link #start()} has run the check stays {@code UP}; a failed startup tears the
 * application down and never reaches it.
 */
@Singleton
public final class StartupReadinessCheck implements HealthCheck, ApplicationStartupStep {

    /** The check's name in the readiness report. */
    public static final String NAME = "startup";

    private volatile boolean started;

    /** Creates the check in its starting state. */
    @Inject
    public StartupReadinessCheck() {}

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Future<HealthCheckResult> check() {
        return Future.succeededFuture(started ? HealthCheckResult.up() : HealthCheckResult.down("starting"));
    }

    @Override
    public LifecyclePhase phase() {
        return LifecyclePhase.AFTER_START;
    }

    @Override
    public int priority() {
        return Integer.MAX_VALUE;
    }

    @Override
    public Future<Void> start() {
        started = true;
        return Future.succeededFuture();
    }
}
