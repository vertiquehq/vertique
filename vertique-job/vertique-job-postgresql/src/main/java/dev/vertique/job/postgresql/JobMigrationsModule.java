// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.postgresql;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.db.MigrationDefinition;
import java.util.List;

/**
 * Dagger module that contributes the job tables' {@link MigrationDefinition} to the
 * {@code Set<MigrationDefinition>} that a migration runner consumes.
 *
 * <p>The definition is metadata only: it names the {@code job} instance and points at the packaged
 * job migration scripts on the classpath ({@code classpath:db/migration/job}). It declares no
 * dependencies, opens no connection and needs no database bindings, so a migration-only component
 * can include it without a connection pool.
 *
 * <p>The instance name {@code job} is also the key under which an application maps the job tables to
 * a schema in its {@code db.schemas} setting; the migrations and {@link PgJobRepository} then place
 * and read the tables in that one schema.
 *
 * <p>{@link JobPostgresqlModule} includes this module, so an application that wires the job
 * repository contributes the definition automatically. A component that only runs migrations can
 * include this module on its own.
 */
@Module
public abstract class JobMigrationsModule {

    /** Name of the job migration instance and key of its schema mapping. */
    static final String INSTANCE_NAME = "job";

    /** Classpath location of the packaged job migration scripts. */
    private static final String LOCATION = "classpath:db/migration/job";

    /**
     * Contributes the job migration declaration.
     *
     * @return the constant definition for the {@code job} instance, with no dependencies
     */
    @Provides
    @IntoSet
    static MigrationDefinition jobMigrationDefinition() {
        return new MigrationDefinition(INSTANCE_NAME, List.of(LOCATION), List.of());
    }
}
