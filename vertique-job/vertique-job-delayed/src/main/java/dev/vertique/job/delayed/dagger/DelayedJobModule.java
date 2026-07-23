// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.delayed.dagger;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.ElementsIntoSet;
import dagger.multibindings.IntoSet;
import dagger.multibindings.Multibinds;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.eventbus.EventBusClient;
import dev.vertique.core.lifecycle.LifecyclePhase;
import dev.vertique.deploy.VerticleDeployment;
import dev.vertique.job.JobCompletionHandler;
import dev.vertique.job.JobCoordinatorConfig;
import dev.vertique.job.JobInterceptor;
import dev.vertique.job.JobRepository;
import dev.vertique.job.dagger.JobCoordinatorModule;
import dev.vertique.job.dagger.JobModule;
import dev.vertique.job.delayed.DefaultDelayedJobTargetResolver;
import dev.vertique.job.delayed.DelayedJobClientFactory;
import dev.vertique.job.delayed.DelayedJobContractContributor;
import dev.vertique.job.delayed.DelayedJobExceptionMapper;
import dev.vertique.job.delayed.DelayedJobHandlerRegistrar;
import dev.vertique.job.delayed.DelayedJobPoller;
import dev.vertique.job.delayed.DelayedJobService;
import dev.vertique.job.delayed.DelayedJobTargetResolver;
import dev.vertique.job.delayed.config.DelayedJobQueueConfig;
import dev.vertique.job.delayed.config.DelayedJobsConfig;
import dev.vertique.job.postgresql.JobPostgresqlModule;
import dev.vertique.job.postgresql.PgJobRepository;
import dev.vertique.logging.LoggingContextModule;
import dev.vertique.services.ServiceContractContributor;
import dev.vertique.services.ServiceContractRegistry;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Dagger module for the delayed job queue.
 *
 * <p>Includes {@link JobModule} for the interceptor multibinding and {@link JobPostgresqlModule}
 * for the {@link JobRepository} binding.
 *
 * <p>Provides:
 * <ul>
 *   <li>{@link DelayedJobService} — enqueues jobs into the persistent queue</li>
 *   <li>{@link DelayedJobHandlerRegistrar} — scans service registry for
 *       {@link dev.vertique.job.delayed.DelayedJobHandlerMethod} annotations and typed
 *       {@link dev.vertique.job.delayed.DelayedJobExecutor} contributors</li>
 *   <li>{@link dev.vertique.job.delayed.DelayedJobClientFactory} — creates JDK dynamic proxies
 *       for {@link dev.vertique.job.delayed.DelayedJobClient} contract interfaces</li>
 *   <li>{@link ServiceContractContributor} via {@code @IntoSet} — registers typed
 *       {@link dev.vertique.job.delayed.DelayedJobExecutor} instances as service contract entries</li>
 *   <li>{@link Set}&lt;{@link VerticleDeployment}&gt; via {@link ElementsIntoSet} — one
 *       {@link DelayedJobPoller} verticle deployment per configured queue</li>
 * </ul>
 *
 * <p>Queue configuration is read from {@code delayedJob.queues} in the application config.
 * If no queues are configured, a single {@code "default"} queue is created with default settings.
 *
 * <p>Include this module in your application Dagger component to enable delayed job scheduling:
 * <pre>{@code
 * @Component(modules = {VertxModule.class, DispatchModule.class, DelayedJobModule.class})
 * public interface AppComponent { ... }
 * }</pre>
 *
 * <p>To contribute custom {@link JobInterceptor}s, add {@code @Provides @IntoSet} methods to
 * your application module:
 * <pre>{@code
 * @Provides @IntoSet
 * static JobInterceptor metricsInterceptor(MetricsService metrics) {
 *     return new MetricsJobInterceptor(metrics);
 * }
 * }</pre>
 */
@Module(
        includes = {
            JobModule.class,
            JobPostgresqlModule.class,
            JobCoordinatorModule.class,
            dev.vertique.context.ContextRuntimeModule.class,
            LoggingContextModule.class
        })
public abstract class DelayedJobModule {

    /**
     * Boundary provider that parses the {@code delayedJob} configuration section into a typed,
     * validated {@link DelayedJobsConfig}. This is the only delayed-job class permitted to inject the
     * raw {@code @VertxConfig JsonObject} (config rule R2): the keyed {@code queues}/{@code contracts}
     * objects are parsed into typed records (key → identity), validated, and indexed; all other
     * delayed-job runtime classes depend on the typed config or its indexes.
     *
     * @param config the application configuration (the boundary's only raw {@code JsonObject})
     * @param parser the injected config parser
     * @return the typed, validated delayed-job config
     */
    @Provides
    @Singleton
    static DelayedJobsConfig delayedJobsConfig(@VertxConfig JsonObject config, ConfigParser parser) {
        return DelayedJobsConfig.fromConfig(config, parser);
    }

    /**
     * Provides the shared job completion handler.
     *
     * <p>The completion handler is wired with the bound {@link JobRepository} so that DB state
     * updates (success/retry/dead-letter) are persisted after each dispatch reply.
     *
     * @param repository the job repository for persistence
     * @return the singleton job completion handler
     */
    @Provides
    @Singleton
    static JobCompletionHandler jobCompletionHandler(JobRepository repository) {
        return new JobCompletionHandler(repository);
    }

    /**
     * Provides the delayed job service.
     *
     * @param repository      the job repository for standalone enqueueing
     * @param pgRepository    the PostgreSQL repository for transactional enqueueing
     * @param registrar       the handler registrar used to resolve handler names to event bus addresses
     * @param propagator      the durable context propagator for merging ambient durable context into
     *                        job metadata at enqueue time (FR-CTX-177)
     * @param exceptionMapper the stage-2 exception mapper that translates data-access failures into
     *                        delayed-job-domain exceptions at the enqueue boundary
     * @return the singleton delayed job service
     */
    @Provides
    @Singleton
    static DelayedJobService delayedJobService(
            JobRepository repository,
            PgJobRepository pgRepository,
            DelayedJobHandlerRegistrar registrar,
            dev.vertique.context.DurableContextPropagator propagator,
            DelayedJobExceptionMapper exceptionMapper) {
        return new DelayedJobService(repository, pgRepository, registrar, propagator, exceptionMapper);
    }

    /**
     * Provides the delayed job handler registrar with the handler map already populated.
     *
     * <p>{@link DelayedJobHandlerRegistrar#scan()} is called eagerly so that the handler address
     * map is fully populated before {@link DelayedJobService} snapshots it in its constructor.
     *
     * @param registry the service contract registry to scan for handler annotations and
     *     contributor-backed job entries
     * @return the singleton handler registrar with a populated handler address map
     */
    @Provides
    @Singleton
    static DelayedJobHandlerRegistrar delayedJobHandlerRegistrar(ServiceContractRegistry registry) {
        DelayedJobHandlerRegistrar registrar = new DelayedJobHandlerRegistrar(registry);
        registrar.scan();
        return registrar;
    }

    /**
     * Provides the set of {@link VerticleDeployment} descriptors for delayed job pollers.
     *
     * <p>By default, one poller instance is deployed per queue configured under
     * {@code delayedJob.queues} in the application config. If no queues are configured, a single
     * {@code "default"} queue is created with default settings. To increase throughput, applications
     * can deploy multiple instances per queue — {@code FOR UPDATE SKIP LOCKED} ensures safe
     * concurrent claiming.
     *
     * <p>All pollers are deployed in the {@link LifecyclePhase#SERVICES} phase at priority 100.
     * The {@code executionTimeoutMs} and {@code progressFlushIntervalMs} values are sourced from
     * the {@link JobCoordinatorConfig} so that all pollers use a consistent timeout policy.
     *
     * @param repository         the job repository for claiming and state updates
     * @param completionHandler  the shared job completion handler
     * @param interceptors       job interceptors to invoke around each dispatch
     * @param delayedJobsConfig  the typed, validated delayed-job config supplying the per-queue settings
     * @param coordinatorConfig  the coordinator configuration supplying timeout and flush interval
     * @param envelopeBuilder    the dispatch envelope builder for context-substrate capture
     * @param propagator         the durable context propagator for decoding persisted metadata into
     *                           each poller's outgoing envelope caller-overrides (FR-CTX-177)
     * @return the set of poller deployment descriptors
     */
    @Provides
    @ElementsIntoSet
    static Set<VerticleDeployment> pollerDeployments(
            JobRepository repository,
            JobCompletionHandler completionHandler,
            Set<JobInterceptor> interceptors,
            EventBusClient eventBusClient,
            DelayedJobsConfig delayedJobsConfig,
            JobCoordinatorConfig coordinatorConfig,
            dev.vertique.context.DispatchEnvelopeBuilder envelopeBuilder,
            dev.vertique.context.DurableContextPropagator propagator) {
        Map<String, DelayedJobQueueConfig> queues = delayedJobsConfig.queueIndex();

        Set<VerticleDeployment> deployments = new HashSet<>();

        Collection<DelayedJobQueueConfig> queueConfigs = queues.isEmpty()
                ? List.of(DelayedJobQueueConfig.builder().name("default").build())
                : queues.values();

        for (DelayedJobQueueConfig queueConfig : queueConfigs) {
            deployments.add(buildPollerDeployment(
                    queueConfig,
                    repository,
                    completionHandler,
                    interceptors,
                    eventBusClient,
                    coordinatorConfig,
                    envelopeBuilder,
                    propagator));
        }

        return deployments;
    }

    /**
     * Declares the empty multibinding set for {@link dev.vertique.job.delayed.DelayedJobExecutor}
     * instances. Application modules contribute executors via {@code @Provides @IntoSet @DelayedJobs}.
     *
     * @return the empty multibinding set (populated by application modules at compile time)
     */
    @Multibinds
    @DelayedJobs
    abstract Set<Object> delayedJobExecutors();

    /**
     * Provides the {@link ServiceContractContributor} that registers typed
     * {@link dev.vertique.job.delayed.DelayedJobExecutor} instances as service contract entries.
     *
     * <p>The contributor is injected into the multibinding set of contributors and invoked by
     * {@link ServiceContractRegistry#build(Set, Set, JsonObject)} during startup to register
     * each executor's event bus address.
     *
     * @param executors the set of executor instances from the {@link DelayedJobs} multibinding
     * @return the contributor, added to the {@link ServiceContractContributor} multibinding
     */
    @Provides
    @IntoSet
    static ServiceContractContributor delayedJobContractContributor(@DelayedJobs Set<Object> executors) {
        return new DelayedJobContractContributor(executors);
    }

    /**
     * Provides the {@link DelayedJobClientFactory} used to create JDK dynamic proxies for
     * typed {@link dev.vertique.job.delayed.DelayedJobClient} contract interfaces.
     *
     * @param jobService        the delayed job service for enqueue delegation
     * @param delayedJobsConfig the typed delayed-job config supplying the per-contract override index
     * @return the singleton client factory
     */
    @Provides
    @Singleton
    static DelayedJobClientFactory delayedJobClientFactory(
            DelayedJobService jobService, DelayedJobsConfig delayedJobsConfig) {
        return new DelayedJobClientFactory(jobService, delayedJobsConfig.contractIndex());
    }

    /**
     * Provides the {@link DelayedJobTargetResolver} for resolving delayed-job targets to their
     * durable identity and effective execution defaults.
     *
     * <p>Built at startup from the service contract registry (delayed-job entries), the handler
     * registrar (for runtime addresses), and application config (for default overrides).
     *
     * @param registry          the service contract registry
     * @param registrar         the handler registrar (already scanned)
     * @param delayedJobsConfig the typed delayed-job config supplying the per-contract override index
     * @return the singleton target resolver
     */
    @Provides
    @Singleton
    static DelayedJobTargetResolver delayedJobTargetResolver(
            ServiceContractRegistry registry,
            DelayedJobHandlerRegistrar registrar,
            DelayedJobsConfig delayedJobsConfig) {
        return new DefaultDelayedJobTargetResolver(registry, registrar, delayedJobsConfig.contractIndex());
    }

    /**
     * Builds a single {@link VerticleDeployment} for a delayed job poller on the given queue.
     *
     * @param queueConfig       the typed, validated per-queue configuration (its {@code name} is the
     *                          logical queue name)
     * @param repository        the job repository
     * @param completionHandler the job completion handler
     * @param interceptors      job interceptors
     * @param eventBusClient    the event bus client for dispatch sends
     * @param coordinatorConfig the coordinator configuration for timeout and flush interval
     * @param envelopeBuilder   the dispatch envelope builder for context-substrate capture
     * @param propagator        the durable context propagator for decoding persisted metadata
     * @return the deployment descriptor
     */
    private static VerticleDeployment buildPollerDeployment(
            DelayedJobQueueConfig queueConfig,
            JobRepository repository,
            JobCompletionHandler completionHandler,
            Set<JobInterceptor> interceptors,
            EventBusClient eventBusClient,
            JobCoordinatorConfig coordinatorConfig,
            dev.vertique.context.DispatchEnvelopeBuilder envelopeBuilder,
            dev.vertique.context.DurableContextPropagator propagator) {
        String queueName = queueConfig.name();
        return new VerticleDeployment(
                "delayed-job-poller-" + queueName,
                () -> new DelayedJobPoller(
                        queueName,
                        queueConfig,
                        repository,
                        completionHandler,
                        interceptors,
                        eventBusClient,
                        coordinatorConfig.executionTimeoutMs(),
                        coordinatorConfig.progressFlushIntervalMs(),
                        envelopeBuilder,
                        propagator),
                new DeploymentOptions().setInstances(queueConfig.instances()),
                LifecyclePhase.SERVICES,
                100);
    }
}
