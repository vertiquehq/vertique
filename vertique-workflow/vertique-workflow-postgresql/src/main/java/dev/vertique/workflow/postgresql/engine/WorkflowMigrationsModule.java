// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.engine;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.db.MigrationDefinition;
import java.util.List;

/**
 * Dagger module that contributes the workflow tables' {@link MigrationDefinition} to the
 * {@code Set<MigrationDefinition>} that a migration runner consumes.
 *
 * <p>The definition is metadata only: it names the {@code workflow} instance and points at the
 * packaged workflow migration scripts on the classpath ({@code classpath:db/migration/workflow}). It
 * declares no dependencies, opens no connection and needs no database bindings, so a migration-only
 * component can include it without a connection pool.
 *
 * <p>The instance name {@code workflow} is also the key under which an application maps the workflow
 * tables to a schema in its {@code db.schemas} setting; the migrations and the PostgreSQL workflow
 * repositories and stores then place and read every workflow table in that one schema.
 *
 * <p>{@link WorkflowPostgresqlModule} includes this module, so an application that wires the
 * PostgreSQL workflow persistence contributes the definition automatically. A component that only
 * runs migrations can include this module on its own.
 *
 * <p>This module concerns database schema migrations only. It is unrelated to
 * {@link dev.vertique.workflow.migration.WorkflowMigrationModule}, which wires the handlers that
 * migrate in-flight workflow instances from one definition version to another.
 */
@Module
public abstract class WorkflowMigrationsModule {

    /** Name of the workflow migration instance and key of its schema mapping. */
    private static final String INSTANCE_NAME = "workflow";

    /** Classpath location of the packaged workflow migration scripts. */
    private static final String LOCATION = "classpath:db/migration/workflow";

    /**
     * Contributes the workflow migration declaration.
     *
     * @return the constant definition for the {@code workflow} instance, with no dependencies
     */
    @Provides
    @IntoSet
    static MigrationDefinition workflowMigrationDefinition() {
        return new MigrationDefinition(INSTANCE_NAME, List.of(LOCATION), List.of());
    }
}
