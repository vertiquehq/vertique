// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.cron.dagger;

import dagger.Module;
import dagger.Provides;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.eventbus.EventBusClient;
import dev.vertique.job.JobCoordinatorConfig;
import dev.vertique.job.JobInterceptor;
import dev.vertique.job.JobRepository;
import dev.vertique.job.cron.CronJobRegistrar;
import dev.vertique.job.cron.CronScheduler;
import dev.vertique.job.cron.config.CronConfig;
import dev.vertique.job.dagger.JobCoordinatorModule;
import dev.vertique.logging.LoggingContextModule;
import dev.vertique.services.ServiceContractRegistry;
import dev.vertique.services.ServiceTargetResolver;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import java.util.Set;

/**
 * Dagger module for cron scheduling with database persistence.
 *
 * <p>Includes {@link CronBaseModule} which pulls in {@link dev.vertique.job.dagger.JobModule}
 * for the job interceptor multibinding.
 *
 * <p>Use this module instead of {@link CronModule} when a {@link JobRepository} binding is
 * available. Enables:
 * <ul>
 *   <li>{@link dev.vertique.job.cron.ExecutionMode#SINGLE_INSTANCE} — INSERT ON CONFLICT
 *       leader election ensures only one node fires per schedule time</li>
 *   <li>Execution tracking — persists {@link dev.vertique.job.JobExecution} records for
 *       dashboard visibility when {@code tracked=true}</li>
 *   <li>Schedule visibility — persists {@link dev.vertique.job.CronJobSchedule} definitions
 *       at startup for the dashboard {@code job_schedules} view</li>
 * </ul>
 *
 * <p>Provides:
 * <ul>
 *   <li>{@link CronScheduler} — manages all job timers, fires interceptors, and coordinates
 *       with the repository for leader election and tracking</li>
 *   <li>{@link CronJobRegistrar} — scans service registry for
 *       {@link dev.vertique.job.cron.CronJob} annotations and persists schedule definitions</li>
 * </ul>
 *
 * <p>Include this module in your application Dagger component instead of {@link CronModule}:
 * <pre>{@code
 * @Component(modules = {VertxModule.class, DispatchModule.class,
 *                       DbPostgresqlModule.class, CronPersistenceModule.class})
 * public interface AppComponent { ... }
 * }</pre>
 */
@Module(
        includes = {
            CronBaseModule.class,
            JobCoordinatorModule.class,
            dev.vertique.context.ContextRuntimeModule.class,
            LoggingContextModule.class
        })
public abstract class CronPersistenceModule {

    /**
     * Provides the {@link CronPersistenceMarker} binding. Bound only by this module; consumers
     * use it as a compose-time proof that the persistence-backed cron flavor is installed.
     * See the marker class javadoc for rationale.
     *
     * @return a new marker instance
     */
    @Provides
    @Singleton
    static CronPersistenceMarker cronPersistenceMarker() {
        return new CronPersistenceMarker();
    }

    /**
     * Provides the DB-backed cron scheduler with repository support, execution timeout, and
     * progress-flush configured from the coordinator config.
     *
     * @param vertx                 the Vert.x instance
     * @param interceptors          the set of job interceptors contributed via Dagger multibinding
     * @param repository            the job repository for leader election and execution tracking
     * @param serviceTargetResolver resolver for stable service target ids to event bus addresses
     * @param config                the coordinator configuration supplying timeout and flush interval
     * @return the singleton cron scheduler
     */
    @Provides
    @Singleton
    static CronScheduler cronScheduler(
            Vertx vertx,
            Set<JobInterceptor> interceptors,
            JobRepository repository,
            ServiceTargetResolver serviceTargetResolver,
            EventBusClient eventBusClient,
            JobCoordinatorConfig config,
            dev.vertique.context.DispatchEnvelopeBuilder envelopeBuilder) {
        return new CronScheduler(
                vertx,
                interceptors,
                repository,
                serviceTargetResolver,
                eventBusClient,
                CronScheduler.DEFAULT_MAX_CONCURRENT_JOBS,
                config.executionTimeoutMs(),
                config.progressFlushIntervalMs(),
                envelopeBuilder);
    }

    /**
     * Parses the {@code cron} subtree of the root config into the typed {@link CronConfig} at the
     * module boundary. This is the only {@code @VertxConfig JsonObject} site in cron scheduling:
     * {@link CronJobRegistrar} below depends on the typed {@link CronConfig}, never the raw root
     * config.
     *
     * @param config the root application configuration
     * @param parser the injected config parser
     * @return the typed, validated cron config
     */
    @Provides
    @Singleton
    static CronConfig cronConfig(@VertxConfig JsonObject config, ConfigParser parser) {
        return CronConfig.fromConfig(config, parser);
    }

    /**
     * Provides the cron job registrar that scans service implementations for
     * {@link dev.vertique.job.cron.CronJob} and persists schedule definitions.
     *
     * @param scheduler             the cron scheduler to register discovered jobs with
     * @param registry              the service contract registry to scan
     * @param serviceTargetResolver resolver for stable service target ids to event bus addresses
     * @param cronConfig            the typed cron configuration
     * @param repository            the job repository for schedule persistence
     * @return the singleton cron job registrar
     */
    @Provides
    @Singleton
    static CronJobRegistrar cronJobRegistrar(
            CronScheduler scheduler,
            ServiceContractRegistry registry,
            ServiceTargetResolver serviceTargetResolver,
            CronConfig cronConfig,
            JobRepository repository) {
        return new CronJobRegistrar(scheduler, registry, serviceTargetResolver, cronConfig, repository);
    }
}
