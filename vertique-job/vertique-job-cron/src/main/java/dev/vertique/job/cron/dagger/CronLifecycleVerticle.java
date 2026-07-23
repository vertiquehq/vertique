// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.cron.dagger;

import dev.vertique.job.cron.CronJobRegistrar;
import dev.vertique.job.cron.CronScheduler;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import lombok.extern.slf4j.Slf4j;

/**
 * Vert.x verticle that owns cron startup and shutdown.
 *
 * <p>On {@link #start(Promise)}, runs {@link CronJobRegistrar#scan()} to validate and load all
 * job definitions, then calls {@link CronScheduler#start()} to arm timers. On
 * {@link #stop(Promise)}, calls {@link CronScheduler#stop()} to cancel timers.
 *
 * <p>Contributed as a {@link dev.vertique.deploy.VerticleDeployment} in
 * {@link dev.vertique.core.lifecycle.LifecyclePhase#SERVICES} (priority 100) by both
 * {@link CronModule} and {@link CronPersistenceModule}, so including either Dagger module is
 * sufficient to wire cron into the application lifecycle — no manual {@code scan()}/{@code start()}
 * calls are required.
 *
 * <p>Package-private: instantiated only by the Dagger providers in this package.
 */
@Slf4j
final class CronLifecycleVerticle extends AbstractVerticle {

    /**
     * Stable {@link dev.vertique.deploy.VerticleDeployment} name for this verticle.
     *
     * <p>Named after the cron-scheduling subsystem (the verticle's purpose) rather than the class
     * (which is an implementation detail). Operators see this name in deployment logs and
     * supervision tooling, so it should describe what the verticle does — running the cron
     * scheduler — not the lifecycle plumbing class that wraps it.
     */
    static final String DEPLOYMENT_NAME = "cron-scheduler";

    private final CronJobRegistrar registrar;
    private final CronScheduler scheduler;

    CronLifecycleVerticle(CronJobRegistrar registrar, CronScheduler scheduler) {
        this.registrar = registrar;
        this.scheduler = scheduler;
    }

    @Override
    public void start(Promise<Void> startPromise) {
        try {
            registrar.scan();
        } catch (Exception t) {
            // Catch Exception (not Throwable) — Errors like OutOfMemoryError put the JVM in an
            // undefined state and should propagate, not be reported as a failed deployment.
            startPromise.fail(t);
            return;
        }
        // Vert.x does not call stop() on a failed start, so any state scan() left in the singleton
        // CronScheduler (registered jobs, partially armed timers) would persist across the failed
        // deployment. Roll back best-effort, but always propagate the original cause.
        Future<Void> startFuture;
        try {
            startFuture = scheduler.start();
        } catch (Exception startCause) {
            // A synchronous throw from scheduler.start() (e.g. vertx.setTimer rejecting on a
            // closed Vert.x) bypasses .onFailure entirely — apply the same rollback path here
            // before failing the deploy promise.
            rollbackAndFail(startPromise, startCause);
            return;
        }
        startFuture
                .onSuccess(v -> startPromise.complete())
                .onFailure(startCause -> rollbackAndFail(startPromise, startCause));
    }

    private void rollbackAndFail(Promise<Void> startPromise, Throwable startCause) {
        scheduler.stop().onComplete(stopAr -> {
            if (stopAr.failed()) {
                log.warn(
                        "Best-effort scheduler.stop() during failed start also failed — "
                                + "scheduler may be in an inconsistent state",
                        stopAr.cause());
            }
            startPromise.fail(startCause);
        });
    }

    @Override
    public void stop(Promise<Void> stopPromise) {
        scheduler.stop().onSuccess(v -> stopPromise.complete()).onFailure(stopPromise::fail);
    }
}
