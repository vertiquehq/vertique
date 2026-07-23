// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.flyway;

import dev.vertique.core.lifecycle.ApplicationStartupStep;
import dev.vertique.core.lifecycle.LifecyclePhase;
import dev.vertique.db.MigrationRunner;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * The {@link LifecyclePhase#MIGRATE MIGRATE}-phase startup step that runs database schema migrations
 * via {@link MigrationRunner#migrate(Vertx)}.
 *
 * <p>This step is the standalone-lifecycle equivalent of the manual {@code migrationRunner().migrate(vertx)}
 * call applications previously made by hand in {@code MainVerticle.start()} before deploying any
 * verticle. It is contributed {@code @IntoSet ApplicationStartupStep} by {@link DbFlywayModule}, so the
 * lifecycle runner invokes it automatically in the {@link LifecyclePhase#MIGRATE} phase — which runs
 * before the {@link LifecyclePhase#INFRA INFRA}, {@link LifecyclePhase#SERVICES SERVICES}, and
 * {@link LifecyclePhase#EDGE EDGE} verticle-deployment phases. The schema is therefore migrated (or
 * validated) before any verticle that depends on it is brought up.
 *
 * <p>This mirrors the {@code SERVICES}-phase service-deploy step in {@code vertique-services}: a single
 * {@link ApplicationStartupStep} contributed by its owning Dagger module, run automatically by the
 * runner in phase order.
 */
@Singleton
public final class FlywayMigrationStartupStep implements ApplicationStartupStep {

    private final MigrationRunner migrationRunner;
    private final Vertx vertx;

    /**
     * Constructs the step.
     *
     * @param migrationRunner the migration runner whose {@link MigrationRunner#migrate(Vertx)} this step
     *     runs in the {@link LifecyclePhase#MIGRATE} phase
     * @param vertx the Vert.x instance passed to {@link MigrationRunner#migrate(Vertx)} (the runner uses
     *     {@code executeBlocking} internally so the blocking JDBC work stays off the event loop)
     */
    @Inject
    public FlywayMigrationStartupStep(MigrationRunner migrationRunner, Vertx vertx) {
        this.migrationRunner = migrationRunner;
        this.vertx = vertx;
    }

    /**
     * Returns the phase this step runs in.
     *
     * @return {@link LifecyclePhase#MIGRATE}
     */
    @Override
    public LifecyclePhase phase() {
        return LifecyclePhase.MIGRATE;
    }

    /**
     * Runs the schema migrations.
     *
     * @return a {@code Future<Void>} completing when migration finishes; a failed migration propagates as
     *     a failed future, aborting startup
     */
    @Override
    public Future<Void> start() {
        return migrationRunner.migrate(vertx).mapEmpty();
    }
}
