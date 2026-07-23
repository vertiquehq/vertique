// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.delayed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.context.ContextScopeBinder;
import dev.vertique.context.DefaultContextHolder;
import dev.vertique.context.DurableContextMetadataRegistry;
import dev.vertique.context.DurableContextPropagator;
import dev.vertique.core.context.ContextDecodeResult;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.core.context.DispatchBoundary;
import dev.vertique.core.context.DurableCarrierDescriptor;
import dev.vertique.core.context.DurableContextMetadataDecoder;
import dev.vertique.core.context.DurableContextMetadataEncoder;
import dev.vertique.core.context.DurableDecodeContext;
import dev.vertique.core.context.DurableEncodeContext;
import dev.vertique.core.context.DurableMetadata;
import dev.vertique.job.JobExecution;
import dev.vertique.job.JobRepository;
import dev.vertique.job.postgresql.PgJobRepository;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Tests for {@link DelayedJobService} durable context metadata capture via
 * {@link DurableContextPropagator#mergeCaptured}. Verifies that:
 *
 * <ul>
 *   <li>Caller-supplied {@link DurableMetadata} persists when no ambient durable context is bound.
 *   <li>Ambient holder-bound durable context is captured into {@link JobExecution#metadata()} via
 *       a {@link TestCorrelationContext} encoder/decoder pair.
 *   <li>A caller-supplied namespace that collides with a bound encoder's namespace throws
 *       {@link IllegalStateException} (FR-CTX-153).
 * </ul>
 */
@ExtendWith({VertxExtension.class, MockitoExtension.class})
@MockitoSettings(strictness = Strictness.LENIENT)
@Timeout(value = 15, unit = TimeUnit.SECONDS)
@DisplayName("DelayedJobService metadata capture")
class DelayedJobServiceMetadataTest {

    // --- Test fixture: TestCorrelationContext ---

    /**
     * Minimal typed durable value used as the test correlation context.
     *
     * @param correlationId the correlation identifier
     * @param tenantId      the tenant identifier
     */
    record TestCorrelationContext(String correlationId, String tenantId) implements ContextValue {}

    /** Namespace used by the test encoder/decoder. */
    private static final String NS = "test-correlation";

    /**
     * Encoder that serialises a {@link TestCorrelationContext} into a single
     * {@code "test-correlation"} namespace body.
     */
    private static final DurableContextMetadataEncoder<TestCorrelationContext> TEST_ENCODER =
            new DurableContextMetadataEncoder<>() {
                @Override
                public Class<TestCorrelationContext> type() {
                    return TestCorrelationContext.class;
                }

                @Override
                public String namespace() {
                    return NS;
                }

                @Override
                public DurableMetadata encode(TestCorrelationContext value, DurableEncodeContext context) {
                    JsonObject body = new JsonObject()
                            .put("correlationId", value.correlationId())
                            .put("tenantId", value.tenantId());
                    return DurableMetadata.of(NS, body);
                }
            };

    /**
     * Decoder that reconstructs a {@link TestCorrelationContext} from the {@code "test-correlation"}
     * namespace body.
     */
    private static final DurableContextMetadataDecoder<TestCorrelationContext> TEST_DECODER =
            new DurableContextMetadataDecoder<>() {
                @Override
                public Class<TestCorrelationContext> type() {
                    return TestCorrelationContext.class;
                }

                @Override
                public String namespace() {
                    return NS;
                }

                @Override
                public ContextDecodeResult<TestCorrelationContext> decode(
                        DurableMetadata metadata, DurableDecodeContext context) {
                    return metadata.body(NS)
                            .map(body -> ContextDecodeResult.of(new TestCorrelationContext(
                                    body.getString("correlationId"), body.getString("tenantId"))))
                            .orElseGet(ContextDecodeResult::empty);
                }
            };

    // --- Helpers ---

    @Mock
    JobRepository repository;

    @Mock
    PgJobRepository pgRepository;

    @Mock
    DelayedJobHandlerRegistrar registrar;

    /**
     * Creates a {@link DurableContextPropagator} backed by the given holder and the test
     * encoder/decoder pair.
     *
     * @param holder the context holder to use
     * @return a propagator wired with the test encoder and decoder
     */
    private static DurableContextPropagator propagatorWith(DefaultContextHolder holder) {
        DurableContextMetadataRegistry registry =
                new DurableContextMetadataRegistry(Set.of(TEST_ENCODER), Set.of(TEST_DECODER));
        return new DurableContextPropagator(registry, holder, new ContextScopeBinder(holder));
    }

    /**
     * Builds a {@link DelayedJobService} using the given propagator and registers a single
     * {@code "my-handler"} address in the mock registrar.
     *
     * @param propagator the propagator to inject
     * @return the configured service
     */
    private DelayedJobService serviceWith(DurableContextPropagator propagator) {
        when(registrar.handlerAddresses()).thenReturn(Map.of("my-handler", "test/svc/handle"));
        return new DelayedJobService(repository, pgRepository, registrar, propagator, new DelayedJobExceptionMapper());
    }

    /** Runs the given task on a fresh duplicated Vert.x context. */
    private static void runOnDuplicated(Vertx vertx, io.vertx.core.Handler<Void> task) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(task);
    }

    // --- Test cases ---

    @Nested
    @DisplayName("caller-supplied metadata")
    class CallerSuppliedMetadata {

        @Test
        @DisplayName("caller-supplied DurableMetadata persists when no ambient durable context is bound")
        void callerMetadataSurvivedWhenNoAmbientContext(Vertx vertx, VertxTestContext ctx) {
            DefaultContextHolder holder = new DefaultContextHolder();
            DelayedJobService service = serviceWith(propagatorWith(holder));

            DurableMetadata callerMeta = DurableMetadata.of("custom", new JsonObject().put("custom-key", "custom-val"));
            DelayedJob job = DelayedJob.builder()
                    .handler("my-handler")
                    .metadata(callerMeta)
                    .build();

            ArgumentCaptor<JobExecution> captor = ArgumentCaptor.forClass(JobExecution.class);
            when(repository.save(captor.capture())).thenReturn(Future.succeededFuture(UUID.randomUUID()));

            // No Vert.x context needed — holder.current() returns empty when no context is active
            service.enqueue(job);

            ctx.verify(() -> {
                DurableMetadata persisted = captor.getValue().metadata();
                assertTrue(persisted.has("custom"), "custom namespace must be present");
                assertEquals(
                        "custom-val",
                        persisted.body("custom").orElseThrow().getString("custom-key"),
                        "custom-key value must survive");
                assertFalse(persisted.has(NS), "encoder namespace must be absent when type is not bound");
            });
            ctx.completeNow();
        }
    }

    @Nested
    @DisplayName("ambient durable context capture")
    class AmbientCapture {

        @Test
        @DisplayName("ambient TestCorrelationContext is encoded and merged into JobExecution.metadata")
        void ambientContextCapturedIntoMetadata(Vertx vertx, VertxTestContext ctx) {
            DefaultContextHolder holder = new DefaultContextHolder();
            DelayedJobService service = serviceWith(propagatorWith(holder));

            ArgumentCaptor<JobExecution> captor = ArgumentCaptor.forClass(JobExecution.class);
            when(repository.save(captor.capture())).thenReturn(Future.succeededFuture(UUID.randomUUID()));

            runOnDuplicated(vertx, v -> {
                try (ContextHolder.Scope scope =
                        holder.bind(TestCorrelationContext.class, new TestCorrelationContext("c-1", "t-1"))) {
                    service.enqueue(DelayedJob.builder().handler("my-handler").build());
                    ctx.verify(() -> {
                        DurableMetadata meta = captor.getValue().metadata();
                        assertTrue(meta.has(NS), "encoder namespace must be present");
                        JsonObject body = meta.body(NS).orElseThrow();
                        assertEquals("c-1", body.getString("correlationId"), "correlationId must be encoded");
                        assertEquals("t-1", body.getString("tenantId"), "tenantId must be encoded");
                    });
                    ctx.completeNow();
                } catch (Throwable t) {
                    ctx.failNow(t);
                }
            });
        }

        @Test
        @DisplayName("ambient context is merged alongside caller-supplied metadata that uses a different namespace")
        void ambientContextMergedWithCallerMetadata(Vertx vertx, VertxTestContext ctx) {
            DefaultContextHolder holder = new DefaultContextHolder();
            DelayedJobService service = serviceWith(propagatorWith(holder));

            ArgumentCaptor<JobExecution> captor = ArgumentCaptor.forClass(JobExecution.class);
            when(repository.save(captor.capture())).thenReturn(Future.succeededFuture(UUID.randomUUID()));

            runOnDuplicated(vertx, v -> {
                try (ContextHolder.Scope scope =
                        holder.bind(TestCorrelationContext.class, new TestCorrelationContext("c-2", "t-2"))) {
                    DurableMetadata callerMeta =
                            DurableMetadata.of("caller-ns", new JsonObject().put("caller-key", "caller-val"));
                    DelayedJob job = DelayedJob.builder()
                            .handler("my-handler")
                            .metadata(callerMeta)
                            .build();
                    service.enqueue(job);
                    ctx.verify(() -> {
                        DurableMetadata meta = captor.getValue().metadata();
                        assertTrue(meta.has(NS), "encoder namespace must be present");
                        assertEquals(
                                "c-2",
                                meta.body(NS).orElseThrow().getString("correlationId"),
                                "correlationId must be captured");
                        assertTrue(meta.has("caller-ns"), "caller namespace must survive merge");
                        assertEquals(
                                "caller-val",
                                meta.body("caller-ns").orElseThrow().getString("caller-key"),
                                "caller-key value must survive merge");
                    });
                    ctx.completeNow();
                } catch (Throwable t) {
                    ctx.failNow(t);
                }
            });
        }
    }

    @Nested
    @DisplayName("namespace collision (FR-CTX-153)")
    class NamespaceCollision {

        @Test
        @DisplayName("collision between caller namespace and bound encoder namespace throws IllegalStateException")
        void collisionThrowsWhenTypeIsBound(Vertx vertx, VertxTestContext ctx) {
            DefaultContextHolder holder = new DefaultContextHolder();
            DelayedJobService service = serviceWith(propagatorWith(holder));

            runOnDuplicated(vertx, v -> {
                try (ContextHolder.Scope scope =
                        holder.bind(TestCorrelationContext.class, new TestCorrelationContext("c-3", "t-3"))) {
                    // Caller supplies the same namespace the encoder produces — collision must throw
                    DurableMetadata callerMeta =
                            DurableMetadata.of(NS, new JsonObject().put("correlationId", "caller-provided"));
                    DelayedJob job = DelayedJob.builder()
                            .handler("my-handler")
                            .metadata(callerMeta)
                            .build();
                    assertThrows(
                            IllegalStateException.class,
                            () -> service.enqueue(job),
                            "enqueue must propagate the collision IllegalStateException from mergeCaptured");
                    ctx.completeNow();
                } catch (Throwable t) {
                    ctx.failNow(t);
                }
            });
        }

        @Test
        @DisplayName(
                "caller namespace matching encoder namespace passes through when encoder type is NOT bound (FR-CTX-154)")
        void callerNamespacePassesThroughWhenTypeNotBound(Vertx vertx, VertxTestContext ctx) {
            DefaultContextHolder holder = new DefaultContextHolder();
            DelayedJobService service = serviceWith(propagatorWith(holder));

            ArgumentCaptor<JobExecution> captor = ArgumentCaptor.forClass(JobExecution.class);
            when(repository.save(captor.capture())).thenReturn(Future.succeededFuture(UUID.randomUUID()));

            runOnDuplicated(vertx, v -> {
                try {
                    // TestCorrelationContext is NOT bound — caller namespace wins
                    DurableMetadata callerMeta =
                            DurableMetadata.of(NS, new JsonObject().put("correlationId", "explicit-corr"));
                    DelayedJob job = DelayedJob.builder()
                            .handler("my-handler")
                            .metadata(callerMeta)
                            .build();
                    service.enqueue(job);
                    ctx.verify(() -> assertEquals(
                            "explicit-corr",
                            captor.getValue().metadata().body(NS).orElseThrow().getString("correlationId"),
                            "caller namespace body must survive when encoder type is not bound (FR-CTX-154)"));
                    ctx.completeNow();
                } catch (Throwable t) {
                    ctx.failNow(t);
                }
            });
        }
    }

    @Nested
    @DisplayName("enqueuePremerged — no-recapture path")
    class EnqueuePremerged {

        @Test
        @DisplayName("enqueuePremerged persists exactly the supplied DurableMetadata — no encoder namespaces added")
        void enqueuePremergedPersistsExactDocument(Vertx vertx, VertxTestContext ctx) {
            DefaultContextHolder holder = new DefaultContextHolder();
            DelayedJobService service = serviceWith(propagatorWith(holder));

            ArgumentCaptor<JobExecution> captor = ArgumentCaptor.forClass(JobExecution.class);
            when(repository.save(captor.capture())).thenReturn(Future.succeededFuture(UUID.randomUUID()));

            // Bind TestCorrelationContext so that if mergeCaptured ran, NS would appear —
            // proving enqueuePremerged bypasses the encoder pipeline.
            runOnDuplicated(vertx, v -> {
                try (ContextHolder.Scope scope =
                        holder.bind(TestCorrelationContext.class, new TestCorrelationContext("c-pm", "t-pm"))) {

                    DurableMetadata premerged =
                            DurableMetadata.of("already-encoded", new JsonObject().put("val", "already-encoded-val"));
                    DelayedJob job = DelayedJob.builder()
                            .handler("my-handler")
                            .metadata(premerged)
                            .build();
                    service.enqueuePremerged(job);

                    ctx.verify(() -> {
                        DurableMetadata persisted = captor.getValue().metadata();
                        // Must contain exactly the pre-merged namespace — no encoder-produced namespaces
                        assertTrue(persisted.has("already-encoded"), "pre-merged namespace must be present");
                        assertEquals(
                                "already-encoded-val",
                                persisted.body("already-encoded").orElseThrow().getString("val"),
                                "pre-merged value must be preserved verbatim");
                        assertFalse(
                                persisted.has(NS),
                                NS + " must not appear: enqueuePremerged must skip encoder pipeline");
                    });
                    ctx.completeNow();
                } catch (Throwable t) {
                    ctx.failNow(t);
                }
            });
        }

        @Test
        @DisplayName(
                "enqueuePremerged(job, SqlClient) persists exactly the supplied DurableMetadata — no encoder namespaces added")
        void enqueuePremergedTransactionalPersistsExactDocument(Vertx vertx, VertxTestContext ctx) {
            DefaultContextHolder holder = new DefaultContextHolder();
            DelayedJobService service = serviceWith(propagatorWith(holder));

            ArgumentCaptor<JobExecution> captor = ArgumentCaptor.forClass(JobExecution.class);
            when(pgRepository.save(captor.capture(), any())).thenReturn(Future.succeededFuture(UUID.randomUUID()));

            runOnDuplicated(vertx, v -> {
                try (ContextHolder.Scope scope =
                        holder.bind(TestCorrelationContext.class, new TestCorrelationContext("c-tx", "t-tx"))) {

                    DurableMetadata premerged = DurableMetadata.of("tx-ns", new JsonObject().put("tx-key", "tx-val"));
                    DelayedJob job = DelayedJob.builder()
                            .handler("my-handler")
                            .metadata(premerged)
                            .build();
                    io.vertx.sqlclient.SqlClient mockClient =
                            org.mockito.Mockito.mock(io.vertx.sqlclient.SqlClient.class);
                    service.enqueuePremerged(job, mockClient);

                    ctx.verify(() -> {
                        DurableMetadata persisted = captor.getValue().metadata();
                        assertTrue(persisted.has("tx-ns"), "tx-ns namespace must be present");
                        assertEquals(
                                "tx-val", persisted.body("tx-ns").orElseThrow().getString("tx-key"));
                        assertFalse(
                                persisted.has(NS),
                                NS + " must not appear: enqueuePremerged must skip encoder pipeline");
                    });
                    ctx.completeNow();
                } catch (Throwable t) {
                    ctx.failNow(t);
                }
            });
        }

        @Test
        @DisplayName(
                "enqueuePremerged rejects premerged metadata carrying the identity-snapshot namespace (fail closed)")
        void enqueuePremergedRejectsIdentityNamespace() {
            DefaultContextHolder holder = new DefaultContextHolder();
            DelayedJobService service = serviceWith(propagatorWith(holder));

            DurableMetadata premerged = DurableMetadata.of(
                    "identity-snapshot",
                    new JsonObject().put("snapshot", "forged").put("present", true));
            DelayedJob job = DelayedJob.builder()
                    .handler("my-handler")
                    .metadata(premerged)
                    .build();

            Future<UUID> result = service.enqueuePremerged(job);

            assertTrue(result.failed(), "premerged identity-snapshot metadata must be rejected");
            assertInstanceOf(IllegalArgumentException.class, result.cause());
            assertTrue(
                    result.cause().getMessage().contains("identity-snapshot"),
                    "rejection message must name the identity-snapshot namespace");
            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("enqueuePremerged(job, SqlClient) rejects the identity-snapshot namespace (fail closed)")
        void enqueuePremergedTransactionalRejectsIdentityNamespace() {
            DefaultContextHolder holder = new DefaultContextHolder();
            DelayedJobService service = serviceWith(propagatorWith(holder));

            DurableMetadata premerged = DurableMetadata.of(
                    "identity-snapshot",
                    new JsonObject().put("snapshot", "forged").put("present", true));
            DelayedJob job = DelayedJob.builder()
                    .handler("my-handler")
                    .metadata(premerged)
                    .build();
            io.vertx.sqlclient.SqlClient mockClient = org.mockito.Mockito.mock(io.vertx.sqlclient.SqlClient.class);

            Future<UUID> result = service.enqueuePremerged(job, mockClient);

            assertTrue(result.failed(), "premerged identity-snapshot metadata must be rejected (transactional)");
            assertInstanceOf(IllegalArgumentException.class, result.cause());
            assertTrue(result.cause().getMessage().contains("identity-snapshot"));
            verify(pgRepository, never()).save(any(), any());
        }

        @Test
        @DisplayName("enqueuePremerged allows premerged metadata that does not carry the identity-snapshot namespace")
        void enqueuePremergedAllowsNonIdentityMetadata() {
            DefaultContextHolder holder = new DefaultContextHolder();
            DelayedJobService service = serviceWith(propagatorWith(holder));

            ArgumentCaptor<JobExecution> captor = ArgumentCaptor.forClass(JobExecution.class);
            when(repository.save(captor.capture())).thenReturn(Future.succeededFuture(UUID.randomUUID()));

            DurableMetadata premerged = DurableMetadata.of("other-ns", new JsonObject().put("k", "v"));
            DelayedJob job = DelayedJob.builder()
                    .handler("my-handler")
                    .metadata(premerged)
                    .build();

            Future<UUID> result = service.enqueuePremerged(job);

            assertTrue(result.succeeded(), "non-identity premerged metadata must pass");
            assertTrue(captor.getValue().metadata().has("other-ns"), "non-identity namespace must be persisted");
        }
    }

    @Nested
    @DisplayName("F5 row-carrier binding (PRD-ID-002 §14.6/A9/F5)")
    class CarrierBinding {

        @Test
        @DisplayName(
                "enqueue binds the row's own carrier: carrierId == persisted execution id, target == delayed-job/handler")
        void scheduleBindsExecutionCarrier(Vertx vertx, VertxTestContext ctx) {
            DefaultContextHolder holder = new DefaultContextHolder();
            java.util.concurrent.atomic.AtomicReference<DurableEncodeContext> observed =
                    new java.util.concurrent.atomic.AtomicReference<>();
            DurableContextMetadataEncoder<TestCorrelationContext> recordingEncoder =
                    new DurableContextMetadataEncoder<>() {
                        @Override
                        public Class<TestCorrelationContext> type() {
                            return TestCorrelationContext.class;
                        }

                        @Override
                        public String namespace() {
                            return NS;
                        }

                        @Override
                        public DurableMetadata encode(TestCorrelationContext value, DurableEncodeContext context) {
                            observed.set(context);
                            return DurableMetadata.of(NS, new JsonObject().put("correlationId", value.correlationId()));
                        }
                    };
            DurableContextMetadataRegistry registry =
                    new DurableContextMetadataRegistry(Set.of(recordingEncoder), Set.of());
            DelayedJobService service =
                    serviceWith(new DurableContextPropagator(registry, holder, new ContextScopeBinder(holder)));

            ArgumentCaptor<JobExecution> captor = ArgumentCaptor.forClass(JobExecution.class);
            when(repository.save(captor.capture())).thenReturn(Future.succeededFuture(UUID.randomUUID()));

            runOnDuplicated(vertx, v -> {
                try (ContextHolder.Scope scope = holder.bind(
                        TestCorrelationContext.class, new TestCorrelationContext("c-carrier", "t-carrier"))) {
                    service.enqueue(DelayedJob.builder().handler("my-handler").build());
                    ctx.verify(() -> {
                        JobExecution saved = captor.getValue();
                        DurableEncodeContext encodeContext = observed.get();
                        assertNotNull(encodeContext, "encoder must have been invoked");
                        assertTrue(
                                encodeContext.carrier().isPresent(),
                                "the delayed-job boundary must thread a real carrier into the encode context");
                        DurableCarrierDescriptor carrier =
                                encodeContext.carrier().orElseThrow();
                        assertEquals(
                                saved.id().toString(),
                                carrier.carrierId(),
                                "carrierId must equal the persisted JobExecution id (single-id allocation)");
                        assertEquals(
                                DispatchBoundary.DELAYED_JOB,
                                carrier.target().kind(),
                                "target kind must be delayed-job");
                        assertEquals(
                                saved.handler(),
                                carrier.target().address(),
                                "target address must be the resolved handler event-bus address");
                    });
                    ctx.completeNow();
                } catch (Throwable t) {
                    ctx.failNow(t);
                }
            });
        }
    }

    @Nested
    @DisplayName("boundary routing")
    class BoundaryRouting {

        @Test
        @DisplayName("mergeCaptured is called with DELAYED_JOB boundary")
        void mergeUsesDelayedJobBoundary(Vertx vertx, VertxTestContext ctx) {
            // Boundary-sensitive encoder: only produces output for the DELAYED_JOB boundary
            DurableContextMetadataEncoder<TestCorrelationContext> boundaryEncoder =
                    new DurableContextMetadataEncoder<>() {
                        @Override
                        public Class<TestCorrelationContext> type() {
                            return TestCorrelationContext.class;
                        }

                        @Override
                        public String namespace() {
                            return NS;
                        }

                        @Override
                        public DurableMetadata encode(TestCorrelationContext value, DurableEncodeContext context) {
                            if (DispatchBoundary.DELAYED_JOB.equals(context.boundary())) {
                                return DurableMetadata.of(
                                        NS, new JsonObject().put("correlationId", value.correlationId()));
                            }
                            // Return empty-body namespace — boundary-sensitive skip
                            return DurableMetadata.of(NS, new JsonObject());
                        }
                    };

            DefaultContextHolder holder = new DefaultContextHolder();
            DurableContextMetadataRegistry registry =
                    new DurableContextMetadataRegistry(Set.of(boundaryEncoder), Set.of());
            DurableContextPropagator propagator =
                    new DurableContextPropagator(registry, holder, new ContextScopeBinder(holder));
            DelayedJobService service = serviceWith(propagator);

            ArgumentCaptor<JobExecution> captor = ArgumentCaptor.forClass(JobExecution.class);
            when(repository.save(captor.capture())).thenReturn(Future.succeededFuture(UUID.randomUUID()));

            runOnDuplicated(vertx, v -> {
                try (ContextHolder.Scope scope =
                        holder.bind(TestCorrelationContext.class, new TestCorrelationContext("c-4", "t-4"))) {
                    service.enqueue(DelayedJob.builder().handler("my-handler").build());
                    ctx.verify(() -> assertEquals(
                            "c-4",
                            captor.getValue().metadata().body(NS).orElseThrow().getString("correlationId"),
                            "boundary-sensitive encoder must produce output for DELAYED_JOB boundary"));
                    ctx.completeNow();
                } catch (Throwable t) {
                    ctx.failNow(t);
                }
            });
        }
    }
}
