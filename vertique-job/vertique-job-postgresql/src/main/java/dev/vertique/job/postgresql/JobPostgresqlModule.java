// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.postgresql;

import dagger.Module;
import dagger.Provides;
import dev.vertique.db.DbSchemas;
import dev.vertique.db.DbSchemasModule;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.job.JobExecutionStateTransitionListener;
import dev.vertique.job.JobRepository;
import dev.vertique.job.dagger.JobModule;
import io.vertx.sqlclient.Pool;
import jakarta.inject.Singleton;
import java.util.Optional;
import java.util.Set;

/**
 * Dagger module that binds {@link JobRepository} to a {@link NotifyingJobRepository} decorator
 * wrapping {@link PgJobRepository}.
 *
 * <p>The binding is split into three {@code @Provides} methods:
 * <ol>
 *   <li>A {@link PgJobRepository} binding built with the optional {@link DbSchemas} placement, so
 *       the job tables are read and written in the schema mapped for the {@code job} instance, and
 *       resolve through the connection's default schema when no mapping exists.</li>
 *   <li>A {@link RawJobRepository}-qualified binding that exposes the bare
 *       {@link PgJobRepository} (the PostgreSQL persistence implementation).</li>
 *   <li>An unqualified {@link JobRepository} binding that wraps the raw repository in a
 *       {@link NotifyingJobRepository} decorator, which fires registered
 *       {@link JobExecutionStateTransitionListener}s after each persisted state transition
 *       ({@code completeExecution} / {@code failAndScheduleRetry} / {@code abandonAndScheduleRetry}).</li>
 * </ol>
 *
 * <p>The {@code includes = JobModule.class} ensures that the
 * {@code Set<JobExecutionStateTransitionListener>} multibinding is declared — the empty set is
 * satisfied automatically even when no listener has been contributed. This keeps the module
 * self-contained for cron-only setups where no listeners are wired.
 *
 * <p>The module also includes {@link JobMigrationsModule}, which contributes the job migration
 * declaration, and {@link DbSchemasModule}, which declares the optional {@link DbSchemas} binding.
 * Components without a {@link DbSchemas} binding keep working with unqualified table names.
 *
 * <p>Include this module in your Dagger component alongside {@code DbPostgresqlModule} to enable
 * database-backed job persistence with state-transition notification:
 *
 * <pre>{@code
 * @Component(modules = {DbPostgresqlModule.class, JobPostgresqlModule.class, ...})
 * interface AppComponent { ... }
 * }</pre>
 */
@Module(includes = {JobModule.class, JobMigrationsModule.class, DbSchemasModule.class})
public abstract class JobPostgresqlModule {

    /**
     * Provides the {@link PgJobRepository}, applying the schema mapping for the {@code job}
     * instance when a {@link DbSchemas} binding exists.
     *
     * <p>Both the {@link RawJobRepository}-qualified alias and, through it, the decorated
     * {@link JobRepository} resolve to this single scoped instance.
     *
     * @param pool    the PostgreSQL connection pool
     * @param mapper  the PostgreSQL failure mapper
     * @param schemas the optional schema placement; absent means unqualified table names
     * @return the scoped PostgreSQL job repository
     */
    @Provides
    @Singleton
    static PgJobRepository pgJobRepository(Pool pool, PgDbExceptionMapper mapper, Optional<DbSchemas> schemas) {
        return new PgJobRepository(pool, mapper, schemas.orElseGet(DbSchemas::empty));
    }

    /**
     * Provides the raw {@link PgJobRepository} qualified as {@link RawJobRepository}.
     *
     * <p>This binding exists solely to break the self-referential cycle that would occur if the
     * unqualified {@link JobRepository} binding were resolved from {@link PgJobRepository}
     * directly — the decorator's constructor would be unsatisfiable.
     *
     * @param impl the PostgreSQL job repository
     * @return the raw {@link JobRepository} implementation
     */
    @Provides
    @Singleton
    @RawJobRepository
    static JobRepository rawJobRepository(PgJobRepository impl) {
        return impl;
    }

    /**
     * Provides the decorated {@link JobRepository} binding that fans out persisted state
     * transitions to all registered {@link JobExecutionStateTransitionListener}s.
     *
     * <p>The {@code listeners} set is always resolvable because {@link JobModule} declares the
     * {@code @Multibinds Set<JobExecutionStateTransitionListener>} empty-set binding.
     *
     * @param raw       the undecorated {@link PgJobRepository} (qualified as
     *                  {@link RawJobRepository})
     * @param listeners the set of state-transition observers contributed via
     *                  {@code @Provides @IntoSet}; may be empty
     * @return the notifying decorator used throughout the application
     */
    @Provides
    @Singleton
    static JobRepository jobRepository(
            @RawJobRepository JobRepository raw, Set<JobExecutionStateTransitionListener> listeners) {
        return new NotifyingJobRepository(raw, listeners);
    }
}
