// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.cron.dagger;

import dagger.Module;
import dagger.Provides;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.eventbus.EventBusClient;
import dev.vertique.job.JobInterceptor;
import dev.vertique.job.cron.CronJobRegistrar;
import dev.vertique.job.cron.CronScheduler;
import dev.vertique.job.cron.config.CronConfig;
import dev.vertique.logging.LoggingContextModule;
import dev.vertique.services.ServiceContractRegistry;
import dev.vertique.services.ServiceTargetResolver;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import java.util.Set;

/**
 * Dagger module for in-memory cron job scheduling (no database persistence).
 *
 * <p>Includes {@link CronBaseModule} which pulls in {@link dev.vertique.job.dagger.JobModule}
 * for the job interceptor multibinding.
 *
 * <p>Use this module when no {@link dev.vertique.job.JobRepository} is available.
 * {@link dev.vertique.job.cron.ExecutionMode#SINGLE_INSTANCE} mode is not available in
 * this configuration. For DB-backed scheduling with SINGLE_INSTANCE support, use
 * {@link CronPersistenceModule} instead.
 *
 * <p>Provides:
 * <ul>
 *   <li>{@link CronScheduler} — manages all job timers and fires registered interceptors</li>
 *   <li>{@link CronJobRegistrar} — scans service registry for
 *       {@link dev.vertique.job.cron.CronJob} annotations</li>
 * </ul>
 *
 * <p>To contribute custom {@link JobInterceptor}s (e.g. for metrics or distributed tracing),
 * add {@code @Provides @IntoSet} methods to your application module:
 * <pre>{@code
 * @Provides @IntoSet
 * static JobInterceptor metricsInterceptor(MetricsService metrics) {
 *     return new MetricsJobInterceptor(metrics);
 * }
 * }</pre>
 *
 * <p>Include this module in your application Dagger component to enable cron scheduling:
 * <pre>{@code
 * @Component(modules = {VertxModule.class, DispatchModule.class, CronModule.class})
 * public interface AppComponent { ... }
 * }</pre>
 */
@Module(includes = {CronBaseModule.class, dev.vertique.context.ContextRuntimeModule.class, LoggingContextModule.class})
public abstract class CronModule {

    /**
     * Provides the in-memory cron scheduler (no repository).
     *
     * @param vertx                 the Vert.x instance
     * @param interceptors          the set of job interceptors contributed via Dagger multibinding
     * @param serviceTargetResolver resolver for stable service target ids to event bus addresses
     * @param envelopeBuilder       dispatch envelope builder for FR-CTX-015 context capture
     * @return the singleton cron scheduler
     */
    @Provides
    @Singleton
    static CronScheduler cronScheduler(
            Vertx vertx,
            Set<JobInterceptor> interceptors,
            ServiceTargetResolver serviceTargetResolver,
            EventBusClient eventBusClient,
            dev.vertique.context.DispatchEnvelopeBuilder envelopeBuilder) {
        return new CronScheduler(vertx, interceptors, null, serviceTargetResolver, eventBusClient, envelopeBuilder);
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
     * {@link dev.vertique.job.cron.CronJob} (no repository).
     *
     * @param scheduler             the cron scheduler to register discovered jobs with
     * @param registry              the service contract registry to scan
     * @param serviceTargetResolver resolver for stable service target ids to event bus addresses
     * @param cronConfig            the typed cron configuration
     * @return the singleton cron job registrar
     */
    @Provides
    @Singleton
    static CronJobRegistrar cronJobRegistrar(
            CronScheduler scheduler,
            ServiceContractRegistry registry,
            ServiceTargetResolver serviceTargetResolver,
            CronConfig cronConfig) {
        return new CronJobRegistrar(scheduler, registry, serviceTargetResolver, cronConfig, null);
    }
}
