// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dagger.BindsInstance;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dev.vertique.context.ContextRuntimeModule;
import dev.vertique.context.InboundDispatchScope;
import dev.vertique.context.InboundExecutionContextScope;
import dev.vertique.context.ServiceDispatchContextRegistry;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.DurableMetadata;
import dev.vertique.core.eventbus.DispatchEnvelope;
import dev.vertique.core.eventbus.LocalMessageCodec;
import dev.vertique.core.eventbus.Result;
import dev.vertique.core.scope.ScopeContext;
import dev.vertique.core.scope.ScopeKey;
import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.inboxoutbox.ClaimScope;
import dev.vertique.inboxoutbox.DestinationType;
import dev.vertique.inboxoutbox.OutboxDeliveryMetadata;
import dev.vertique.inboxoutbox.OutboxDestinationHandler;
import dev.vertique.inboxoutbox.OutboxEntry;
import dev.vertique.inboxoutbox.OutboxEntryDisposition;
import dev.vertique.inboxoutbox.OutboxEnvelope;
import dev.vertique.inboxoutbox.OutboxMetadata;
import dev.vertique.inboxoutbox.OutboxPublishCompletedEvent;
import dev.vertique.inboxoutbox.OutboxPublishObserver;
import dev.vertique.inboxoutbox.OutboxPublishOutcome;
import dev.vertique.inboxoutbox.OutboxPublishResult;
import dev.vertique.inboxoutbox.OutboxRelayConfig;
import dev.vertique.inboxoutbox.OutboxRepository;
import dev.vertique.inboxoutbox.RelayStrategy;
import dev.vertique.inboxoutbox.services.ServiceOutboxDestinationHandler;
import dev.vertique.inboxoutbox.services.TransactionalMessagingServiceModule;
import dev.vertique.resilience.ResolvedResiliencePolicy;
import dev.vertique.resilience.annotation.ResilienceAnnotations;
import dev.vertique.services.ResolvedServiceTarget;
import dev.vertique.services.ServiceExceptionMapper;
import dev.vertique.services.ServiceSupervisor;
import dev.vertique.services.ServiceTargetResolver;
import dev.vertique.services.dispatch.ServiceMethodDescriptor;
import dev.vertique.services.dispatch.ServiceMethodInvoker;
import dev.vertique.services.dispatch.ServiceMethodMeta;
import dev.vertique.services.dispatch.ServiceMethodMeta.ParamMeta;
import dev.vertique.services.dispatch.ServiceMethodMeta.ParamSource;
import dev.vertique.services.interceptor.ServiceDispatchContext;
import dev.vertique.services.interceptor.ServiceInterceptor;
import dev.vertique.services.resilience.ServiceResilienceConfigAdapter;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.MessageConsumer;
import io.vertx.core.internal.ContextInternal;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/** Proves saved outbox scope through the real relay, service destination, and service receiver. */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class DurableScopeOutboxRelayIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("outbox_durable_scope")
            .withMigration("classpath:db/migration/inbox-outbox");
    private static final String TARGET = "test.scope.receive";
    private static final String ADDRESS = "test.scope.receiver";
    private static final ScopeContext ACME = scope("acme");
    private static final ScopeContext PREVIOUS = scope("receiver-before");
    private static Vertx vertx;
    private static Pool pool;
    private static PgConnectOptions connectOptions;
    private static ServiceGraph graph;
    private static MessageConsumer<DispatchEnvelope<?>> consumer;
    private static final Probe probe = new Probe();
    private String relayDeployment;

    static final class Probe {
        boolean failing;
        ScopeContext receiverPrevious;
        Optional<ScopeContext> installed = Optional.empty();
        Map<String, Object> receivedMap = Map.of();
        ContextHolder.Scope previousBinding;
        Promise<ReceiverReceipt> closedReceiver;

        void reset() {
            failing = false;
            receiverPrevious = null;
            installed = Optional.empty();
            receivedMap = Map.of();
            previousBinding = null;
            closedReceiver = Promise.promise();
        }
    }

    record ReceiverReceipt(
            Optional<ScopeContext> installed,
            Optional<ScopeContext> after,
            boolean emptyAfterFixtureClose,
            boolean scopeEntryPresent,
            boolean failed) {}

    record RelayReceipt(OutboxPublishCompletedEvent event, JsonObject context, ReceiverReceipt receiver) {}

    /** The method is invoked reflectively by the production service receiver. */
    public static final class Receiver {
        private final ContextHolder holder;
        private final Probe probe;

        @Inject
        Receiver(ContextHolder holder, Probe probe) {
            this.holder = holder;
            this.probe = probe;
        }

        /** Reads installed scope before completing asynchronously on the receiving duplicate. */
        public Future<String> receive(String payload) {
            probe.installed = holder.current(ScopeContext.class);
            Promise<String> completion = Promise.promise();
            Vertx.currentContext().runOnContext(ignored -> {
                if (probe.failing) {
                    completion.fail(new IllegalStateException("asynchronous service failure"));
                } else {
                    completion.complete(payload);
                }
            });
            return completion.future();
        }
    }

    @Module
    static class FixtureModule {
        @Provides
        @Singleton
        static OutboxRepository repository(Pool pool) {
            return new PgInboxOutboxRepository(pool, new PgDbExceptionMapper());
        }

        @Provides
        @Singleton
        static ServiceMethodMeta metadata(Receiver receiver) {
            try {
                return ServiceMethodMeta.ofDirect(
                        receiver,
                        ServiceMethodDescriptor.of(Receiver.class.getMethod("receive", String.class)),
                        ADDRESS,
                        TARGET,
                        "test",
                        "scope",
                        "receive",
                        String.class,
                        String.class,
                        List.of(new ParamMeta("payload", ParamSource.PAYLOAD, String.class)),
                        ResilienceAnnotations.NONE,
                        List.of(),
                        List.of(),
                        false);
            } catch (NoSuchMethodException failure) {
                throw new AssertionError(failure);
            }
        }

        @Provides
        @Singleton
        static ServiceTargetResolver resolver(ServiceMethodMeta meta) {
            ServiceTargetResolver resolver = mock(ServiceTargetResolver.class);
            when(resolver.resolve(TARGET)).thenReturn(ResolvedServiceTarget.of(Receiver.class, meta));
            when(resolver.supportedTargetIds()).thenReturn(Set.of(TARGET));
            return resolver;
        }

        @Provides
        @Singleton
        static ServiceSupervisor supervisor() {
            ServiceSupervisor supervisor = mock(ServiceSupervisor.class);
            when(supervisor.isAvailable(Receiver.class)).thenReturn(true);
            return supervisor;
        }

        @Provides
        @Singleton
        static ServiceResilienceConfigAdapter resilience() {
            ServiceResilienceConfigAdapter adapter = mock(ServiceResilienceConfigAdapter.class);
            ResolvedResiliencePolicy empty = new ResolvedResiliencePolicy(
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
            when(adapter.resolve(any())).thenReturn(empty);
            when(adapter.explicitSendTimeoutMs(any())).thenReturn(2_000L);
            when(adapter.derivedSendTimeoutMs(any(), any())).thenReturn(2_000L);
            return adapter;
        }

        @Provides
        @Singleton
        static ServiceMethodInvoker invoker(
                ServiceMethodMeta meta,
                Vertx vertx,
                ServiceDispatchContextRegistry registry,
                InboundDispatchScope inbound,
                InboundExecutionContextScope execution,
                ContextHolder holder,
                Probe probe) {
            ServiceInterceptor terminal = new ServiceInterceptor() {
                @Override
                public void onTerminalComplete(
                        ServiceDispatchContext dispatch, Result<?> result, Instant start, Instant end) {
                    ContextInternal receiver = (ContextInternal) Vertx.currentContext();
                    receiver.runOnContext(ignored -> {
                        try {
                            Optional<ScopeContext> after = holder.current(ScopeContext.class);
                            if (probe.previousBinding != null) {
                                probe.previousBinding.close();
                            }
                            probe.closedReceiver.tryComplete(new ReceiverReceipt(
                                    probe.installed,
                                    after,
                                    holder.current(ScopeContext.class).isEmpty(),
                                    probe.receivedMap.containsKey(ScopeContext.class.getName()),
                                    result.isFailure()));
                        } catch (Throwable failure) {
                            probe.closedReceiver.tryFail(failure);
                        } finally {
                            if (probe.previousBinding != null) {
                                probe.previousBinding.close();
                            }
                        }
                    });
                }
            };
            return new ServiceMethodInvoker(
                    meta,
                    new ServiceExceptionMapper(),
                    List.of(terminal),
                    null,
                    null,
                    vertx,
                    registry,
                    inbound,
                    execution);
        }
    }

    @Singleton
    @Component(modules = {ContextRuntimeModule.class, TransactionalMessagingServiceModule.class, FixtureModule.class})
    interface ServiceGraph {
        ContextHolder holder();

        DefaultOutboxService producer();

        OutboxRepository repository();

        ServiceOutboxDestinationHandler destination();

        ServiceMethodInvoker invoker();

        @Component.Factory
        interface Factory {
            ServiceGraph create(@BindsInstance Vertx vertx, @BindsInstance Pool pool, @BindsInstance Probe probe);
        }
    }

    @BeforeAll
    static void setUp(Vertx injectedVertx) {
        vertx = injectedVertx;
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
        graph = DaggerDurableScopeOutboxRelayIT_ServiceGraph.factory().create(vertx, pool, probe);
        vertx.eventBus().registerCodec(new LocalMessageCodec<>("dispatch.envelope"));
        vertx.eventBus().registerCodec(new LocalMessageCodec<>("dispatch.result"));
        consumer = vertx.eventBus().consumer(ADDRESS, message -> {
            ContextInternal receiver = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
            receiver.runOnContext(ignored -> {
                try {
                    probe.receivedMap = Map.copyOf(message.body().metadata().dispatchContext());
                    if (probe.receiverPrevious != null) {
                        probe.previousBinding = graph.holder().bind(ScopeContext.class, probe.receiverPrevious);
                    }
                    graph.invoker().handle(message);
                } catch (Throwable failure) {
                    if (probe.previousBinding != null) {
                        probe.previousBinding.close();
                    }
                    probe.closedReceiver.tryFail(failure);
                    message.fail(500, "service receiver fixture failed");
                }
            });
        });
    }

    @BeforeEach
    void reset() throws Exception {
        await(pool.query("TRUNCATE outbox, inbox RESTART IDENTITY").execute());
        probe.reset();
    }

    @AfterEach
    void stopRelay() throws Exception {
        if (relayDeployment != null) {
            String deployment = relayDeployment;
            relayDeployment = null;
            await(vertx.undeploy(deployment));
        }
        if (probe.previousBinding != null) {
            probe.previousBinding.close();
        }
    }

    @AfterAll
    static void tearDown() throws Exception {
        try {
            if (consumer != null) {
                await(consumer.unregister());
            }
        } finally {
            if (pool != null) {
                await(pool.close());
            }
        }
    }

    @Test
    @DisplayName("a fresh real service receiver restores exact acme from producer-captured persisted outbox metadata")
    void restoresScopeFromPersistedOutboxMetadataOnFreshServiceReceiver(VertxTestContext ctx) {
        publish(ACME)
                .compose(id -> persistedContext(id).map(context -> {
                    assertEquals(
                            savedScope().toJson(),
                            context,
                            "the outbox row must contain the exact captured v1 scope body");
                    return id;
                }))
                .compose(this::relay)
                .onComplete(ctx.succeeding(receipt -> ctx.verify(() -> {
                    assertEquals(savedScope().toJson(), receipt.context());
                    assertEquals(
                            Optional.of(ACME),
                            receipt.receiver().installed(),
                            "the real receiver must observe the saved acme scope");
                    assertTrue(receipt.receiver().scopeEntryPresent());
                    assertTrue(receipt.receiver().after().isEmpty());
                    assertTrue(receipt.receiver().emptyAfterFixtureClose());
                    assertEquals(
                            OutboxEntryDisposition.PUBLISHED, receipt.event().disposition());
                    assertTrue(receipt.event().dispositionRecorded());
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("service success closes the installed saved scope and restores the receiver's previous binding")
    void closesBindingAfterServiceSuccess(VertxTestContext ctx) {
        probe.receiverPrevious = PREVIOUS;
        insert(savedScope())
                .compose(this::relay)
                .onComplete(ctx.succeeding(receipt -> ctx.verify(() -> {
                    assertEquals(
                            Optional.of(ACME),
                            receipt.receiver().installed(),
                            "saved scope must be installed before success cleanup is credited");
                    assertEquals(Optional.of(PREVIOUS), receipt.receiver().after());
                    assertTrue(receipt.receiver().emptyAfterFixtureClose());
                    assertFalse(receipt.receiver().failed());
                    assertEquals(OutboxPublishOutcome.SUCCESS, receipt.event().outcome());
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("asynchronous service failure closes the installed saved scope and restores the prior binding")
    void closesBindingAfterAsyncServiceFailure(VertxTestContext ctx) {
        probe.receiverPrevious = PREVIOUS;
        probe.failing = true;
        insert(savedScope())
                .compose(this::relay)
                .onComplete(ctx.succeeding(receipt -> ctx.verify(() -> {
                    assertEquals(
                            Optional.of(ACME),
                            receipt.receiver().installed(),
                            "saved scope must be installed before asynchronous failure cleanup is credited");
                    assertEquals(Optional.of(PREVIOUS), receipt.receiver().after());
                    assertTrue(receipt.receiver().emptyAfterFixtureClose());
                    assertTrue(receipt.receiver().failed());
                    assertEquals(
                            OutboxPublishOutcome.RETRYABLE_FAILURE,
                            receipt.event().outcome());
                    assertEquals(
                            OutboxEntryDisposition.DEAD_LETTERED,
                            receipt.event().disposition());
                    assertTrue(receipt.event().dispositionRecorded());
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("absent saved scope leaves a fresh service receiver without a scope binding")
    void doesNotBindScopeOnFreshReceiverWhenMetadataIsAbsent(VertxTestContext ctx) {
        publish(null)
                .compose(this::relay)
                .onComplete(ctx.succeeding(receipt -> ctx.verify(() -> {
                    assertFalse(receipt.context().containsKey("scope"));
                    assertFalse(receipt.receiver().scopeEntryPresent());
                    assertTrue(receipt.receiver().installed().isEmpty());
                    assertTrue(receipt.receiver().after().isEmpty());
                    assertTrue(receipt.receiver().emptyAfterFixtureClose());
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("an omitted dispatch-map scope entry preserves the receiver's existing binding")
    void preservesExistingReceiverBindingWhenDispatchMapOmitsScope(VertxTestContext ctx) {
        probe.receiverPrevious = PREVIOUS;
        insert(DurableMetadata.empty())
                .compose(this::relay)
                .onComplete(ctx.succeeding(receipt -> ctx.verify(() -> {
                    assertFalse(
                            receipt.receiver().scopeEntryPresent(),
                            "the destination must omit scope when none was decoded");
                    assertEquals(
                            Optional.of(PREVIOUS),
                            receipt.receiver().installed(),
                            "the real service receiver overlays only entries present in its dispatch map");
                    assertEquals(Optional.of(PREVIOUS), receipt.receiver().after());
                    assertTrue(receipt.receiver().emptyAfterFixtureClose());
                    ctx.completeNow();
                })));
    }

    private static OutboxEntry entry() {
        return OutboxEntry.builder()
                .destinationType(DestinationType.SERVICE)
                .destination(TARGET)
                .eventType("scope.receipt")
                .payload("payload")
                .maxAttempts(1)
                .build();
    }

    private static Future<Long> publish(ScopeContext senderScope) {
        return onFreshDuplicate(() -> {
            ContextHolder.Scope sender =
                    senderScope == null ? () -> {} : graph.holder().bind(ScopeContext.class, senderScope);
            try {
                return pool.withTransaction(tx -> graph.producer().publish(tx, entry()))
                        .eventually(() -> {
                            sender.close();
                            return Future.succeededFuture();
                        });
            } catch (Throwable failure) {
                sender.close();
                return Future.failedFuture(failure);
            }
        });
    }

    private static Future<Long> insert(DurableMetadata context) {
        return pool.withTransaction(tx -> graph.repository()
                .insert(entry(), new OutboxMetadata(context, OutboxDeliveryMetadata.empty()), UUID.randomUUID(), tx));
    }

    private static Future<JsonObject> persistedContext(Long id) {
        return pool.preparedQuery("SELECT metadata FROM outbox WHERE id = $1")
                .execute(Tuple.of(id))
                .map(rows ->
                        rows.iterator().next().getJsonObject("metadata").getJsonObject("context", new JsonObject()));
    }

    private Future<RelayReceipt> relay(Long id) {
        Promise<RelayReceipt> completed = Promise.promise();
        OutboxDestinationHandler independentRelay = new OutboxDestinationHandler() {
            @Override
            public DestinationType destinationType() {
                return DestinationType.SERVICE;
            }

            @Override
            public ClaimScope claimScope() {
                return graph.destination().claimScope();
            }

            @Override
            public Future<OutboxPublishResult> publish(OutboxEnvelope envelope) {
                return onFreshDuplicate(() -> {
                    ContextHolder.Scope ambient = graph.holder().bind(ScopeContext.class, scope("relay-ambient"));
                    try {
                        return graph.destination().publish(envelope).eventually(() -> {
                            ambient.close();
                            return Future.succeededFuture();
                        });
                    } catch (Throwable failure) {
                        ambient.close();
                        return Future.failedFuture(failure);
                    }
                });
            }
        };
        OutboxPublishObserver observer = new OutboxPublishObserver() {
            @Override
            public void onPublishCompleted(OutboxPublishCompletedEvent event, OutboxEnvelope envelope) {
                if (id.toString().equals(event.entryId())) {
                    JsonObject context = envelope.metadata().context().toJson();
                    probe.closedReceiver.future().onComplete(receiver -> {
                        if (receiver.succeeded()) {
                            completed.tryComplete(new RelayReceipt(event, context, receiver.result()));
                        } else {
                            completed.tryFail(receiver.cause());
                        }
                    });
                }
            }
        };
        Map<DestinationType, OutboxDestinationHandler> handlers = OutboxRelay.buildHandlerMap(Set.of(independentRelay));
        OutboxRelayConfig config = OutboxRelayConfig.builder()
                .pollingIntervalMs(10L)
                .batchSize(1)
                .strategy(RelayStrategy.POLLING)
                .build();
        OutboxRelay relay = new OutboxRelay(
                config,
                graph.repository(),
                handlers,
                OutboxRelay.deriveCapabilities(handlers),
                connectOptions,
                "scope-relay",
                List.of(observer));
        return vertx.deployVerticle(relay).compose(deployment -> {
            relayDeployment = deployment;
            return completed.future().eventually(() -> {
                relayDeployment = null;
                return vertx.undeploy(deployment);
            });
        });
    }

    private static ScopeContext scope(String key) {
        return new ScopeContext(new ScopeKey(key));
    }

    private static DurableMetadata savedScope() {
        return DurableMetadata.of(
                "scope", new JsonObject().put("schemaVersion", 1).put("scopeKey", "acme"));
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
