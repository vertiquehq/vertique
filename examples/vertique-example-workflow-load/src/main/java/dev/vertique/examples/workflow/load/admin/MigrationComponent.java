// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.admin;

import dagger.BindsInstance;
import dagger.Component;
import dev.vertique.config.parser.ConfigParsingModule;
import dev.vertique.core.VertxConfig;
import dev.vertique.db.DbModule;
import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.flyway.DbFlywayModule;
import dev.vertique.db.flyway.FlywayMigrationRunner;
import dev.vertique.inboxoutbox.postgresql.InboxOutboxMigrationsModule;
import dev.vertique.job.postgresql.JobMigrationsModule;
import dev.vertique.workflow.postgresql.engine.WorkflowMigrationsModule;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;

/**
 * Migration-only graph: the Flyway runner and the three migration declarations the engine, the outbox and the cron
 * scheduler need. It binds no connection pool, so running it opens only the migration's own connection.
 *
 * <p>With {@code flyway.named} set, each declaration becomes its own Flyway instance with its own history table:
 * {@code flyway_schema_history_workflow}, {@code flyway_schema_history_inbox_outbox} and
 * {@code flyway_schema_history_job}. The three script sets all start at version 1 and could not share one history.
 */
@Singleton
@Component(
        modules = {
            DbModule.class,
            DbFlywayModule.class,
            ConfigParsingModule.class,
            InboxOutboxMigrationsModule.class,
            WorkflowMigrationsModule.class,
            JobMigrationsModule.class
        })
interface MigrationComponent {

    /**
     * Returns the Flyway runner for the declared instances.
     *
     * @return the runner
     */
    FlywayMigrationRunner runner();

    /**
     * Returns the typed database configuration, for the connections of the trigger commands.
     *
     * @return the database configuration
     */
    DbPoolConfig dbPoolConfig();

    /** Builds the graph from a Vert.x instance and a resolved configuration tree. */
    @Component.Factory
    interface Factory {

        /**
         * Creates the component.
         *
         * @param vertx the Vert.x instance the migration runs its blocking work on
         * @param config the resolved configuration tree
         * @return the component
         */
        MigrationComponent create(@BindsInstance Vertx vertx, @BindsInstance @VertxConfig JsonObject config);
    }
}
