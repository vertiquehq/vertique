// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.delayed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dagger.BindsInstance;
import dagger.Component;
import dev.vertique.context.ContextRuntimeModule;
import dev.vertique.context.DispatchEnvelopeBuilder;
import dev.vertique.context.DurableContextPropagator;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.DurableMetadata;
import dev.vertique.core.eventbus.DispatchEnvelope;
import dev.vertique.core.eventbus.EventBusClient;
import dev.vertique.core.scope.ScopeContext;
import dev.vertique.core.scope.ScopeKey;
import dev.vertique.job.JobCompletionHandler;
import dev.vertique.job.JobExecution;
import dev.vertique.job.JobRepository;
import dev.vertique.job.JobState;
import dev.vertique.job.JobType;
import dev.vertique.job.ProgressSnapshot;
import dev.vertique.job.delayed.config.DelayedJobQueueConfig;
import dev.vertique.job.postgresql.PgJobRepository;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/** Proves ordinary capture and seeded durable decode at the poller's dispatch-map seam. */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class DelayedJobScopeContextTest {

    private static final String HANDLER = "scope-job";
    private static final String ADDRESS = "test.delayed.scope";
    private static final ScopeContext ACME = new ScopeContext(new ScopeKey("acme"));

    @Singleton
    @Component(modules = ContextRuntimeModule.class)
    interface JobGraph {
        ContextHolder holder();

        DurableContextPropagator propagator();

        DispatchEnvelopeBuilder envelopeBuilder();

        DelayedJobService service();

        @Component.Factory
        interface Factory {
            JobGraph create(
                    @BindsInstance JobRepository repository,
                    @BindsInstance PgJobRepository pgRepository,
                    @BindsInstance DelayedJobHandlerRegistrar registrar);
        }
    }

    @Test
    @DisplayName("an ordinary delayed job captures scope and the poller restores it in the dispatch map")
    void restoresScopeForOrdinaryJob(Vertx vertx, VertxTestContext ctx) {
        JobRepository repository = mock(JobRepository.class);
        JobGraph graph = graph(repository);
        AtomicReference<JobExecution> persisted = new AtomicReference<>();
        when(repository.save(any())).thenAnswer(invocation -> {
            JobExecution execution = invocation.getArgument(0);
            persisted.set(execution);
            return Future.succeededFuture(execution.id());
        });
        Promise<UUID> enqueued = Promise.promise();
        ContextInternal producer = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        producer.runOnContext(ignored -> {
            ContextHolder.Scope scope = graph.holder().bind(ScopeContext.class, ACME);
            try {
                graph.service()
                        .enqueue(DelayedJob.builder()
                                .handler(HANDLER)
                                .payload("ordinary")
                                .build())
                        .eventually(() -> {
                            scope.close();
                            return Future.succeededFuture();
                        })
                        .onComplete(enqueued);
            } catch (Throwable failure) {
                scope.close();
                enqueued.fail(failure);
            }
        });
        enqueued.future()
                .compose(id -> receiveDispatch(
                        vertx, graph, repository, processing(id, persisted.get().metadata())))
                .onComplete(ctx.succeeding(envelope -> ctx.verify(() -> {
                    assertEquals(
                            ACME,
                            envelope.metadata().dispatchContext().get(ScopeContext.class.getName()),
                            "the ordinary job receiver must receive the decoded saved scope");
                    assertEquals(
                            scopeBody(),
                            persisted.get().metadata().body("scope").orElseThrow());
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("the poller decodes an independently seeded outbox v1 body at DELAYED_JOB")
    void decodesPersistedOutboxScopeAtDelayedJobBoundary(Vertx vertx, VertxTestContext ctx) {
        JobRepository repository = mock(JobRepository.class);
        JobGraph graph = graph(repository);
        DurableMetadata savedOutboxScope = DurableMetadata.of("scope", scopeBody());
        receiveDispatch(vertx, graph, repository, processing(UUID.randomUUID(), savedOutboxScope))
                .onComplete(ctx.succeeding(envelope -> ctx.verify(() -> {
                    assertEquals(
                            ACME,
                            envelope.metadata().dispatchContext().get(ScopeContext.class.getName()),
                            "the seeded scope must decode at the poller's DELAYED_JOB dispatch-map seam");
                    ctx.completeNow();
                })));
    }

    private static JobGraph graph(JobRepository repository) {
        DelayedJobHandlerRegistrar registrar = mock(DelayedJobHandlerRegistrar.class);
        when(registrar.handlerAddresses()).thenReturn(Map.of(HANDLER, ADDRESS));
        return DaggerDelayedJobScopeContextTest_JobGraph.factory()
                .create(repository, mock(PgJobRepository.class), registrar);
    }

    /** Receives the production poller's outgoing envelope; it deliberately installs no holder values. */
    private static Future<DispatchEnvelope<?>> receiveDispatch(
            Vertx vertx, JobGraph graph, JobRepository repository, JobExecution execution) {
        when(repository.claimNextJob(anyString(), anyInt()))
                .thenReturn(Future.succeededFuture(List.of(execution)))
                .thenReturn(Future.succeededFuture(List.of()));
        Promise<DispatchEnvelope<?>> received = Promise.promise();
        EventBusClient client = mock(EventBusClient.class);
        doAnswer(invocation -> {
                    received.tryComplete(invocation.getArgument(1));
                    return null;
                })
                .when(client)
                .send(anyString(), any());
        DelayedJobPoller poller = new DelayedJobPoller(
                "default",
                DelayedJobQueueConfig.builder()
                        .sleepDelayMs(10L)
                        .maxConcurrentJobs(1)
                        .build(),
                repository,
                mock(JobCompletionHandler.class),
                Set.of(),
                client,
                graph.envelopeBuilder(),
                graph.propagator());
        return vertx.deployVerticle(poller)
                .compose(deployment -> received.future().eventually(() -> vertx.undeploy(deployment)));
    }

    private static JobExecution processing(UUID id, DurableMetadata metadata) {
        return new JobExecution(
                id,
                HANDLER,
                JobType.DELAYED,
                ADDRESS,
                "default",
                JobState.PROCESSING,
                0,
                3,
                "payload",
                0,
                null,
                Instant.now(),
                null,
                null,
                null,
                null,
                null,
                ProgressSnapshot.EMPTY,
                Map.of(),
                Map.of(),
                metadata);
    }

    private static JsonObject scopeBody() {
        return new JsonObject().put("schemaVersion", 1).put("scopeKey", "acme");
    }
}
