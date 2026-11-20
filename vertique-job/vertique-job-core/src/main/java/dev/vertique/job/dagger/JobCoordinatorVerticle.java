// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.dagger;

import dev.vertique.core.lifecycle.LifecyclePhase;
import dev.vertique.deploy.VerticleDeployment;
import dev.vertique.job.JobCoordinator;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import java.util.Objects;

/**
 * INTERNAL framework seam — not an application contract and outside the maturity promise.
 *
 * <p>Vert.x verticle that owns the {@link JobCoordinator} lifecycle: {@link #start(Promise)} calls
 * {@link JobCoordinator#start()} and {@link #stop(Promise)} calls {@link JobCoordinator#stop()}, so
 * the coordinator's timers run on this verticle's context.
 *
 * <p>Contributed by {@link JobCoordinatorModule} in the {@code SERVICES} phase at
 * {@link #DEPLOYMENT_PRIORITY}, below the delayed-job pollers and the cron
 * scheduler. Deployment runs in ascending priority and undeployment in descending priority, so the
 * claimers deploy only after the initial heartbeat write has settled, and the coordinator's shutdown
 * claim check runs only after every claimer has stopped.
 *
 * <p>Package-private: instantiated only by the Dagger provider in this package.
 */
final class JobCoordinatorVerticle extends AbstractVerticle {

    /** Deployment name of the coordinator verticle, shown in deployment logs. */
    static final String DEPLOYMENT_NAME = "job-coordinator";

    /**
     * {@code SERVICES} priority of the coordinator deployment: below the priority 100 at which the
     * delayed-job pollers and the cron scheduler deploy, so the coordinator deploys before them and
     * undeploys after them.
     */
    static final int DEPLOYMENT_PRIORITY = 50;

    private final JobCoordinator coordinator;

    JobCoordinatorVerticle(JobCoordinator coordinator) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator must not be null");
    }

    /**
     * Builds the coordinator deployment: {@code SERVICES} phase at {@link #DEPLOYMENT_PRIORITY}.
     *
     * @param coordinator the coordinator to start and stop
     * @return the deployment descriptor
     */
    static VerticleDeployment deployment(JobCoordinator coordinator) {
        return VerticleDeployment.of(
                DEPLOYMENT_NAME,
                () -> new JobCoordinatorVerticle(coordinator),
                LifecyclePhase.SERVICES,
                DEPLOYMENT_PRIORITY);
    }

    @Override
    public void start(Promise<Void> startPromise) {
        coordinator.start().onComplete(startPromise);
    }

    @Override
    public void stop(Promise<Void> stopPromise) {
        coordinator.stop().onComplete(stopPromise);
    }
}
