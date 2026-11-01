// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dagger.BindsInstance;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dev.vertique.context.ContextRuntimeModule;
import dev.vertique.context.DispatchEnvelopeBuilder;
import dev.vertique.context.DurableContextPropagator;
import dev.vertique.core.eventbus.DispatchEnvelope;
import dev.vertique.core.eventbus.EventBusClient;
import dev.vertique.core.eventbus.Result;
import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.inboxoutbox.DestinationType;
import dev.vertique.inboxoutbox.OutboxDestinationHandler;
import dev.vertique.inboxoutbox.OutboxEnvelope;
import dev.vertique.inboxoutbox.OutboxPublishCompletedEvent;
import dev.vertique.inboxoutbox.OutboxPublishObserver;
import dev.vertique.inboxoutbox.OutboxRelayConfig;
import dev.vertique.inboxoutbox.OutboxRepository;
import dev.vertique.inboxoutbox.OutboxService;
import dev.vertique.inboxoutbox.RelayStrategy;
import dev.vertique.inboxoutbox.delayedjob.DelayedJobOutboxDestinationHandler;
import dev.vertique.inboxoutbox.delayedjob.TransactionalDelayedJobPublisher;
import dev.vertique.job.JobCompletionHandler;
import dev.vertique.job.JobContext;
import dev.vertique.job.JobExecution;
import dev.vertique.job.JobRepository;
import dev.vertique.job.delayed.DelayedJobClient;
import dev.vertique.job.delayed.DelayedJobHandlerRegistrar;
import dev.vertique.job.delayed.DelayedJobPoller;
import dev.vertique.job.delayed.DelayedJobTargetResolver;
import dev.vertique.job.delayed.ResolvedDelayedJobTarget;
import dev.vertique.job.delayed.config.DelayedJobQueueConfig;
import dev.vertique.job.postgresql.PgJobRepository;
import dev.vertique.resilience.BackoffStrategy;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.eventbus.Message;
import io.vertx.core.eventbus.MessageConsumer;
import io.vertx.core.internal.ContextInternal;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/** Proves one scope from a committed publish through the real relay and poller to the job handler. */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class ScopedOutboxToDelayedJobIT {

    static final PostgresContainer db = new PostgresContainer().withDatabaseName("outbox_scoped_delayed_job");
    private static final String HANDLER = "scoped-job";
    private static final String ADDRESS = "test.scoped.job";
    private static final String QUEUE = "default";
    private static final ResolvedDelayedJobTarget TARGET =
            new ResolvedDelayedJobTarget(HANDLER, HANDLER, ADDRESS, QUEUE, 0, 1);
    private static final DeliveryOptions REPLY_OPTIONS = new DeliveryOptions().setCodecName("dispatch.envelope");
    private static Vertx vertx;
    private static Pool pool;
    private static PgConnectOptions connectOptions;
    private static JobGraph graph;

    private final Promise<JobContext> handled = Promise.promise();
    private final Promise<Void> jobCompleted = Promise.promise();
    private final Promise<Void> relayed = Promise.promise();
    private MessageConsumer<DispatchEnvelope<?>> consumer;
    private String relayDeployment;
    private String pollerDeployment;

    private interface ScopedJob extends DelayedJobClient<JsonObject> {}

    @Module
    static class FixtureModule {
        @Provides
        @Singleton
        static OutboxRepository outboxRepository(Pool pool) {
            return new PgInboxOutboxRepository(pool, new PgDbExceptionMapper());
        }

        @Provides
        @Singleton
        static OutboxService outboxService(DefaultOutboxService service) {
            return service;
        }

        @Provides
        @Singleton
        static PgJobRepository pgJobRepository(Pool pool) {
            return new PgJobRepository(pool, new PgDbExceptionMapper());
        }

        @Provides
        @Singleton
        static JobRepository jobRepository(PgJobRepository repository) {
            return repository;
        }

        @Provides
        @Singleton
        static DelayedJobHandlerRegistrar registrar() {
            DelayedJobHandlerRegistrar registrar = mock(DelayedJobHandlerRegistrar.class);
            when(registrar.handlerAddresses()).thenReturn(Map.of(HANDLER, ADDRESS));
            return registrar;
        }

        @Provides
        @Singleton
        static DelayedJobTargetResolver resolver() {
            DelayedJobTargetResolver resolver = mock(DelayedJobTargetResolver.class);
            when(resolver.resolve(ScopedJob.class)).thenReturn(TARGET);
            when(resolver.resolve(HANDLER)).thenReturn(TARGET);
            when(resolver.supportedTargetIds()).thenReturn(Set.of(HANDLER));
            return resolver;
        }
    }

    @Singleton
    @Component(modules = {ContextRuntimeModule.class, FixtureModule.class})
    interface JobGraph {
        TransactionalDelayedJobPublisher publisher();

        DelayedJobOutboxDestinationHandler destination();

        OutboxRepository outboxRepository();

        JobRepository jobRepository();

        EventBusClient eventBusClient();

        DispatchEnvelopeBuilder envelopeBuilder();

        DurableContextPropagator propagator();

        @Component.Factory
        interface Factory {
            JobGraph create(@BindsInstance Vertx vertx, @BindsInstance Pool pool);
        }
    }

    @BeforeAll
    static void setUp(Vertx injectedVertx) {
        vertx = injectedVertx;
        // Each history gets its own Flyway run, so the second finds a non-empty schema and baselines below V1
        Flyway.configure()
                .dataSource(db.jdbcUrl(), db.username(), db.password())
                .locations("classpath:db/migration/job")
                .table("flyway_job_history")
                .load()
                .migrate();
        Flyway.configure()
                .dataSource(db.jdbcUrl(), db.username(), db.password())
                .locations("classpath:db/migration/inbox-outbox")
                .table("flyway_inbox_outbox_history")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate();
        DbPoolConfig config = db.toPoolConfig();
        connectOptions = new PgConnectOptions()
                .setHost(config.host())
                .setPort(config.port())
                .setDatabase(config.database())
                .setUser(config.user())
                .setPassword(config.password());
        pool = PgBuilder.pool()
                .with(new PoolOptions().setMaxSize(4))
                .connectingTo(connectOptions)
                .using(vertx)
                .build();
        graph = DaggerScopedOutboxToDelayedJobIT_JobGraph.factory().create(vertx, pool);
    }

    @BeforeEach
    void startRelayAndPoller() throws Exception {
        consumer = vertx.eventBus().consumer(ADDRESS, this::handle);
        await(consumer.completion());
        pollerDeployment = await(vertx.deployVerticle(poller()));
        relayDeployment = await(vertx.deployVerticle(relay()));
    }

    @AfterEach
    void stopRelayAndPoller() throws Exception {
        try {
            if (relayDeployment != null) {
                await(vertx.undeploy(relayDeployment));
            }
        } finally {
            try {
                if (pollerDeployment != null) {
                    await(vertx.undeploy(pollerDeployment));
                }
            } finally {
                if (consumer != null) {
                    await(consumer.unregister());
                }
            }
        }
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (pool != null) {
            await(pool.close());
        }
    }

    @Test
    @DisplayName("a publish committed in acme reports acme on the outbox row, the job execution and the handler")
    void scopeFollowsPublishToHandler() throws Exception {
        Long entryId = await(onFreshDuplicate(() -> pool.withTransaction(
                tx -> graph.publisher().publish(ScopedJob.class, new JsonObject().put("order", 1), null, "acme", tx))));

        JobContext job = await(handled.future());
        await(jobCompleted.future());
        await(relayed.future());

        assertEquals("acme", storedScope("SELECT scope_key FROM outbox WHERE id = $1", Tuple.of(entryId)));
        assertEquals(
                "acme", storedScope("SELECT scope_key FROM job_executions WHERE id = $1", Tuple.of(job.executionId())));
        assertEquals("acme", job.scopeKey());
    }

    /** Records the context as {@code DelayedJobPoller} puts it, then reports success to its reply address. */
    private void handle(Message<DispatchEnvelope<?>> message) {
        DispatchEnvelope<?> dispatch = message.body();
        handled.tryComplete((JobContext) dispatch.metadata().dispatchContext().get(JobContext.class.getName()));
        vertx.eventBus()
                .send(dispatch.replyAddress().orElseThrow(), DispatchEnvelope.of(Result.success(null)), REPLY_OPTIONS);
    }

    private DelayedJobPoller poller() {
        JobCompletionHandler completion = new JobCompletionHandler(graph.jobRepository()) {
            @Override
            public Future<Void> handleCompletion(
                    JobExecution execution, Result<?> result, BackoffStrategy backoffStrategy) {
                return super.handleCompletion(execution, result, backoffStrategy)
                        .onComplete(jobCompleted);
            }
        };
        return new DelayedJobPoller(
                QUEUE,
                DelayedJobQueueConfig.builder()
                        .sleepDelayMs(10L)
                        .maxConcurrentJobs(1)
                        .build(),
                graph.jobRepository(),
                completion,
                Set.of(),
                graph.eventBusClient(),
                graph.envelopeBuilder(),
                graph.propagator());
    }

    private OutboxRelay relay() {
        OutboxPublishObserver observer = new OutboxPublishObserver() {
            @Override
            public void onPublishCompleted(OutboxPublishCompletedEvent event, OutboxEnvelope envelope) {
                relayed.tryComplete();
            }
        };
        Map<DestinationType, OutboxDestinationHandler> handlers =
                OutboxRelay.buildHandlerMap(Set.of(graph.destination()));
        OutboxRelayConfig config = OutboxRelayConfig.builder()
                .pollingIntervalMs(10L)
                .batchSize(1)
                .strategy(RelayStrategy.POLLING)
                .build();
        return new OutboxRelay(
                config,
                graph.outboxRepository(),
                handlers,
                OutboxRelay.deriveCapabilities(handlers),
                connectOptions,
                "scoped-job-relay",
                List.of(observer));
    }

    private static String storedScope(String sql, Tuple id) throws Exception {
        return await(pool.preparedQuery(sql).execute(id)).iterator().next().getString("scope_key");
    }

    private static <T> Future<T> onFreshDuplicate(Supplier<Future<T>> action) {
        Promise<T> result = Promise.promise();
        ContextInternal duplicate = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        duplicate.runOnContext(ignored -> {
            try {
                action.get().onComplete(result);
            } catch (Throwable failure) {
                result.fail(failure);
            }
        });
        return result.future();
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}
