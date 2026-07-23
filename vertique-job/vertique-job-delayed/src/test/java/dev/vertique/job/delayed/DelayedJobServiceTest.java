// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.delayed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.context.ContextScopeBinder;
import dev.vertique.context.DefaultContextHolder;
import dev.vertique.context.DurableContextMetadataRegistry;
import dev.vertique.context.DurableContextPropagator;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.core.context.DurableContextMetadataEncoder;
import dev.vertique.core.context.DurableEncodeContext;
import dev.vertique.core.context.DurableMetadata;
import dev.vertique.core.exception.DurableEncodeRejectedException;
import dev.vertique.db.exception.DataAccessException;
import dev.vertique.job.JobExecution;
import dev.vertique.job.JobRepository;
import dev.vertique.job.JobState;
import dev.vertique.job.JobType;
import dev.vertique.job.delayed.exception.DelayedJobPersistenceException;
import dev.vertique.job.postgresql.PgJobRepository;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tests for {@link DelayedJobService}: enqueue state logic, jobId generation, handler address
 * resolution, input validation, and transactional enqueue.
 */
@DisplayName("DelayedJobService")
@ExtendWith({MockitoExtension.class, VertxExtension.class})
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class DelayedJobServiceTest {

    @Mock
    JobRepository repository;

    @Mock
    PgJobRepository pgRepository;

    @Mock
    DelayedJobHandlerRegistrar registrar;

    /** Real event bus address that the registrar resolves for "my-handler". */
    private static final String HANDLER_ADDRESS = "test/my-svc/sendEmail";

    DelayedJobService service;

    /** Creates a no-op {@link DurableContextPropagator} with no encoders or decoders. */
    private static DurableContextPropagator noOpPropagator() {
        DefaultContextHolder holder = new DefaultContextHolder();
        return new DurableContextPropagator(
                new DurableContextMetadataRegistry(Set.of(), Set.of()), holder, new ContextScopeBinder(holder));
    }

    @BeforeEach
    void setUp() {
        // The registrar returns a real address map so address-resolution tests work without DB
        when(registrar.handlerAddresses())
                .thenReturn(Map.of(
                        "my-handler", HANDLER_ADDRESS,
                        "h", "test/svc/h",
                        "send-email", "test/svc/sendEmail"));
        service = new DelayedJobService(
                repository, pgRepository, registrar, noOpPropagator(), new DelayedJobExceptionMapper());
    }

    @Nested
    @DisplayName("enqueue state")
    class EnqueueState {

        @Test
        @DisplayName("creates ENQUEUED state when runAt is null")
        void enqueuesWhenRunAtIsNull() {
            UUID savedId = UUID.randomUUID();
            when(repository.save(any())).thenReturn(Future.succeededFuture(savedId));

            DelayedJob job = DelayedJob.builder().handler("my-handler").build();
            service.enqueue(job);

            ArgumentCaptor<JobExecution> captor = ArgumentCaptor.forClass(JobExecution.class);
            verify(repository).save(captor.capture());
            assertEquals(JobState.ENQUEUED, captor.getValue().state());
        }

        @Test
        @DisplayName("creates ENQUEUED state when runAt is in the past")
        void enqueuesWhenRunAtIsInPast() {
            UUID savedId = UUID.randomUUID();
            when(repository.save(any())).thenReturn(Future.succeededFuture(savedId));

            DelayedJob job = DelayedJob.builder()
                    .handler("my-handler")
                    .runAt(Instant.now().minusSeconds(60))
                    .build();
            service.enqueue(job);

            ArgumentCaptor<JobExecution> captor = ArgumentCaptor.forClass(JobExecution.class);
            verify(repository).save(captor.capture());
            assertEquals(JobState.ENQUEUED, captor.getValue().state());
        }

        @Test
        @DisplayName("creates ENQUEUED state when runAt is in the future (scheduled_at holds future time)")
        void enqueuesWhenRunAtIsInFuture() {
            UUID savedId = UUID.randomUUID();
            when(repository.save(any())).thenReturn(Future.succeededFuture(savedId));

            Instant future = Instant.now().plusSeconds(60);
            DelayedJob job =
                    DelayedJob.builder().handler("my-handler").runAt(future).build();
            service.enqueue(job);

            ArgumentCaptor<JobExecution> captor = ArgumentCaptor.forClass(JobExecution.class);
            verify(repository).save(captor.capture());
            // Always ENQUEUED — claimNextJob filters by scheduled_at <= NOW()
            assertEquals(JobState.ENQUEUED, captor.getValue().state());
            // The future time is preserved in scheduledAt so the poller won't claim it early
            assertTrue(captor.getValue().scheduledAt().isAfter(Instant.now()), "scheduledAt should be in the future");
        }
    }

    @Nested
    @DisplayName("jobId generation")
    class JobIdGeneration {

        @Test
        @DisplayName("auto-generates jobId when not provided")
        void autoGeneratesJobId() {
            when(repository.save(any())).thenReturn(Future.succeededFuture(UUID.randomUUID()));

            DelayedJob job = DelayedJob.builder().handler("h").build();
            service.enqueue(job);

            ArgumentCaptor<JobExecution> captor = ArgumentCaptor.forClass(JobExecution.class);
            verify(repository).save(captor.capture());
            assertNotNull(captor.getValue().jobId());
            assertTrue(captor.getValue().jobId().startsWith("delayed-"));
        }

        @Test
        @DisplayName("uses provided jobId when set")
        void usesProvidedJobId() {
            when(repository.save(any())).thenReturn(Future.succeededFuture(UUID.randomUUID()));

            DelayedJob job =
                    DelayedJob.builder().handler("h").jobId("my-stable-id").build();
            service.enqueue(job);

            ArgumentCaptor<JobExecution> captor = ArgumentCaptor.forClass(JobExecution.class);
            verify(repository).save(captor.capture());
            assertEquals("my-stable-id", captor.getValue().jobId());
        }
    }

    @Nested
    @DisplayName("handler address resolution")
    class HandlerAddress {

        @Test
        @DisplayName("resolves handler name to real event bus address")
        void resolvesHandlerToRealAddress() {
            when(repository.save(any())).thenReturn(Future.succeededFuture(UUID.randomUUID()));

            DelayedJob job = DelayedJob.builder().handler("send-email").build();
            service.enqueue(job);

            ArgumentCaptor<JobExecution> captor = ArgumentCaptor.forClass(JobExecution.class);
            verify(repository).save(captor.capture());
            assertEquals("test/svc/sendEmail", captor.getValue().handler());
        }

        @Test
        @DisplayName("sets job type to DELAYED")
        void setsJobTypeDelayed() {
            when(repository.save(any())).thenReturn(Future.succeededFuture(UUID.randomUUID()));

            DelayedJob job = DelayedJob.builder().handler("h").build();
            service.enqueue(job);

            ArgumentCaptor<JobExecution> captor = ArgumentCaptor.forClass(JobExecution.class);
            verify(repository).save(captor.capture());
            assertEquals(JobType.DELAYED, captor.getValue().jobType());
        }

        @Test
        @DisplayName("sets attempt to 0 on first enqueue")
        void setsAttemptZero() {
            when(repository.save(any())).thenReturn(Future.succeededFuture(UUID.randomUUID()));

            DelayedJob job = DelayedJob.builder().handler("h").build();
            service.enqueue(job);

            ArgumentCaptor<JobExecution> captor = ArgumentCaptor.forClass(JobExecution.class);
            verify(repository).save(captor.capture());
            assertEquals(0, captor.getValue().attemptNumber());
        }

        @Test
        @DisplayName("uses provided maxAttempts")
        void usesProvidedMaxAttempts() {
            when(repository.save(any())).thenReturn(Future.succeededFuture(UUID.randomUUID()));

            DelayedJob job = DelayedJob.builder().handler("h").maxAttempts(7).build();
            service.enqueue(job);

            ArgumentCaptor<JobExecution> captor = ArgumentCaptor.forClass(JobExecution.class);
            verify(repository).save(captor.capture());
            assertEquals(7, captor.getValue().maxAttempts());
        }
    }

    @Nested
    @DisplayName("validation")
    class Validation {

        @Test
        @DisplayName("returns failed future for null handler")
        void failsOnNullHandler() {
            DelayedJob job = DelayedJob.builder().handler(null).build();
            Future<UUID> result = service.enqueue(job);
            assertTrue(result.failed());
            assertInstanceOf(IllegalArgumentException.class, result.cause());
        }

        @Test
        @DisplayName("returns failed future for handler with illegal characters")
        void failsOnIllegalHandlerChars() {
            // Simulate an unknown handler to confirm validation fires for invalid pattern
            DelayedJob job = DelayedJob.builder().handler("bad handler!").build();
            Future<UUID> result = service.enqueue(job);
            assertTrue(result.failed());
            assertInstanceOf(IllegalArgumentException.class, result.cause());
            assertTrue(result.cause().getMessage().contains("Invalid handler name"));
        }

        @Test
        @DisplayName("returns failed future for unknown handler")
        void failsOnUnknownHandler() {
            DelayedJob job = DelayedJob.builder().handler("unknown-handler").build();
            Future<UUID> result = service.enqueue(job);
            assertTrue(result.failed());
            assertInstanceOf(IllegalArgumentException.class, result.cause());
            assertTrue(result.cause().getMessage().contains("Unknown delayed job handler"));
        }

        @Test
        @DisplayName("returns failed future when maxAttempts is zero")
        void failsOnMaxAttemptsZero() {
            // maxAttempts=0 is only possible by explicit builder call (default is 3)
            DelayedJob job =
                    DelayedJob.builder().handler("my-handler").maxAttempts(0).build();
            Future<UUID> result = service.enqueue(job);
            assertTrue(result.failed());
            assertInstanceOf(IllegalArgumentException.class, result.cause());
            assertTrue(result.cause().getMessage().contains("maxAttempts must be between 1 and 1000"));
        }

        @Test
        @DisplayName("returns failed future when maxAttempts exceeds 1000")
        void failsOnMaxAttemptsOver1000() {
            DelayedJob job =
                    DelayedJob.builder().handler("my-handler").maxAttempts(1001).build();
            Future<UUID> result = service.enqueue(job);
            assertTrue(result.failed());
            assertInstanceOf(IllegalArgumentException.class, result.cause());
            assertTrue(result.cause().getMessage().contains("maxAttempts must be between 1 and 1000"));
        }

        @Test
        @DisplayName("accepts maxAttempts at boundary values 1 and 1000")
        void acceptsBoundaryMaxAttempts() {
            when(repository.save(any())).thenReturn(Future.succeededFuture(UUID.randomUUID()));

            DelayedJob jobMin = DelayedJob.builder().handler("h").maxAttempts(1).build();
            assertTrue(service.enqueue(jobMin).succeeded());

            DelayedJob jobMax =
                    DelayedJob.builder().handler("h").maxAttempts(1000).build();
            assertTrue(service.enqueue(jobMax).succeeded());
        }
    }

    @Nested
    @DisplayName("transactional enqueue")
    class TransactionalEnqueue {

        @Test
        @DisplayName("uses pgRepository with provided SqlClient")
        void usesPgRepositoryWithClient() {
            io.vertx.sqlclient.SqlClient client = org.mockito.Mockito.mock(io.vertx.sqlclient.SqlClient.class);
            UUID savedId = UUID.randomUUID();
            when(pgRepository.save(any(), any())).thenReturn(Future.succeededFuture(savedId));

            DelayedJob job = DelayedJob.builder().handler("h").build();
            service.enqueue(job, client);

            verify(pgRepository).save(any(JobExecution.class), any());
        }

        @Test
        @DisplayName("resolves handler to real address in transactional enqueue")
        void resolvesAddressInTransactionalEnqueue() {
            io.vertx.sqlclient.SqlClient client = org.mockito.Mockito.mock(io.vertx.sqlclient.SqlClient.class);
            when(pgRepository.save(any(), any())).thenReturn(Future.succeededFuture(UUID.randomUUID()));

            DelayedJob job = DelayedJob.builder().handler("my-handler").build();
            service.enqueue(job, client);

            ArgumentCaptor<JobExecution> captor = ArgumentCaptor.forClass(JobExecution.class);
            verify(pgRepository).save(captor.capture(), any());
            assertEquals(HANDLER_ADDRESS, captor.getValue().handler());
        }

        @Test
        @DisplayName("returns failed future on unknown handler in transactional enqueue")
        void failsOnUnknownHandlerTransactional() {
            io.vertx.sqlclient.SqlClient client = org.mockito.Mockito.mock(io.vertx.sqlclient.SqlClient.class);
            DelayedJob job = DelayedJob.builder().handler("not-registered").build();
            Future<UUID> result = service.enqueue(job, client);
            assertTrue(result.failed());
            assertInstanceOf(IllegalArgumentException.class, result.cause());
        }
    }

    @Nested
    @DisplayName("persistence exception wrapping")
    class PersistenceExceptionWrapping {

        @Test
        @DisplayName("DataAccessException from repository is wrapped as DelayedJobPersistenceException")
        void dataAccessExceptionIsWrapped() {
            DataAccessException dbFailure = new DataAccessException("raw db detail", new RuntimeException("sql cause"));
            when(repository.save(any())).thenReturn(Future.failedFuture(dbFailure));

            DelayedJob job = DelayedJob.builder().handler("my-handler").build();
            Future<UUID> result = service.enqueue(job);

            assertTrue(result.failed());
            assertInstanceOf(DelayedJobPersistenceException.class, result.cause());
            // The original DataAccessException must be the cause (cause chain preserved)
            assertInstanceOf(DataAccessException.class, result.cause().getCause());
        }
    }

    /**
     * F5 doomed-expiry fire-time threading (PRD-ID-002 §14.6 A9): verifies that {@code toExecution}
     * threads {@link DelayedJob#runAt()} (or "now" when unset) into the encode context's
     * {@link DurableEncodeContext#fireTime()}, and that a
     * {@link DurableEncodeRejectedException} thrown by a durable-context encoder — the exact shape
     * {@code IdentitySnapshotDurableEncoder} throws under its FAIL policy when the row is doomed to
     * expire before it fires — surfaces from {@link DelayedJobService#enqueue} as a failed
     * {@link Future}, never as an uncaught exception. A fake in-module encoder stands in for
     * {@code IdentitySnapshotDurableEncoder} so this proof does not require a cross-module test
     * dependency on {@code vertique-security-runtime} (see the {@code IDENTITY_SNAPSHOT_NAMESPACE}
     * javadoc on {@link DelayedJobService}).
     */
    @Nested
    @DisplayName("F5 doomed-expiry fire-time threading")
    class DoomedExpiryFireTimeThreading {

        /** Minimal {@link ContextValue} test double bound to trigger {@link #propagatorWithFakeEncoder}. */
        private static final class FakeCtx implements ContextValue {}

        /**
         * Runs the given task on a duplicated Vert.x context so the holder accepts writes.
         *
         * @param vertx the Vert.x instance
         * @param task  the task to run on the duplicated context
         */
        private static void runOnDuplicated(Vertx vertx, Handler<Void> task) {
            ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
            dup.runOnContext(task);
        }

        /**
         * Builds a {@link DurableContextPropagator} wired with a single fake encoder for
         * {@link FakeCtx} that records the {@link DurableEncodeContext} it observes and, when
         * {@code rejecting} is {@code true}, throws {@link DurableEncodeRejectedException} — standing
         * in for {@code IdentitySnapshotDurableEncoder}'s F5 doomed-expiry rejection.
         *
         * @param holder    the context holder the fake value is bound into and the propagator reads
         *                  from
         * @param observed  captures the last {@link DurableEncodeContext} the fake encoder observed
         * @param rejecting when {@code true}, the fake encoder throws
         *                  {@link DurableEncodeRejectedException} instead of encoding
         * @return the wired propagator
         */
        private static DurableContextPropagator propagatorWithFakeEncoder(
                DefaultContextHolder holder, AtomicReference<DurableEncodeContext> observed, boolean rejecting) {
            DurableContextMetadataEncoder<FakeCtx> fakeEncoder = new DurableContextMetadataEncoder<>() {
                @Override
                public Class<FakeCtx> type() {
                    return FakeCtx.class;
                }

                @Override
                public String namespace() {
                    return "fake-ns";
                }

                @Override
                public DurableMetadata encode(FakeCtx value, DurableEncodeContext context) {
                    observed.set(context);
                    if (rejecting) {
                        throw new DurableEncodeRejectedException(
                                "fake encoder rejects — models the F5 doomed-expiry FAIL policy");
                    }
                    return DurableMetadata.of("fake-ns", new JsonObject().put("v", "x"));
                }
            };
            DurableContextMetadataRegistry registry = new DurableContextMetadataRegistry(Set.of(fakeEncoder), Set.of());
            return new DurableContextPropagator(registry, holder, new ContextScopeBinder(holder));
        }

        @Test
        @DisplayName("enqueue threads runAt as the encode context's fireTime")
        void enqueueThreadsRunAtAsFireTime(Vertx vertx, VertxTestContext ctx) {
            DefaultContextHolder holder = new DefaultContextHolder();
            AtomicReference<DurableEncodeContext> observed = new AtomicReference<>();
            DurableContextPropagator propagator = propagatorWithFakeEncoder(holder, observed, false);
            DelayedJobService svc = new DelayedJobService(
                    repository, pgRepository, registrar, propagator, new DelayedJobExceptionMapper());
            when(repository.save(any())).thenReturn(Future.succeededFuture(UUID.randomUUID()));
            Instant runAt = Instant.now().plusSeconds(120);
            DelayedJob job =
                    DelayedJob.builder().handler("my-handler").runAt(runAt).build();

            runOnDuplicated(vertx, v -> {
                try (ContextHolder.Scope scope = holder.bind(FakeCtx.class, new FakeCtx())) {
                    svc.enqueue(job);
                    assertNotNull(observed.get(), "the fake encoder must have been invoked");
                    assertEquals(
                            runAt,
                            observed.get().fireTime().orElseThrow(),
                            "the encode context's fireTime must equal the job's runAt");
                    ctx.completeNow();
                } catch (Throwable t) {
                    ctx.failNow(t);
                }
            });
        }

        @Test
        @DisplayName("enqueue with no runAt threads an approximately-now fireTime")
        void enqueueWithNoRunAtThreadsApproxNowFireTime(Vertx vertx, VertxTestContext ctx) {
            DefaultContextHolder holder = new DefaultContextHolder();
            AtomicReference<DurableEncodeContext> observed = new AtomicReference<>();
            DurableContextPropagator propagator = propagatorWithFakeEncoder(holder, observed, false);
            DelayedJobService svc = new DelayedJobService(
                    repository, pgRepository, registrar, propagator, new DelayedJobExceptionMapper());
            when(repository.save(any())).thenReturn(Future.succeededFuture(UUID.randomUUID()));
            DelayedJob job = DelayedJob.builder().handler("my-handler").build();

            runOnDuplicated(vertx, v -> {
                try (ContextHolder.Scope scope = holder.bind(FakeCtx.class, new FakeCtx())) {
                    Instant before = Instant.now();
                    svc.enqueue(job);
                    Instant after = Instant.now();
                    assertNotNull(observed.get(), "the fake encoder must have been invoked");
                    Instant fireTime = observed.get().fireTime().orElseThrow();
                    assertTrue(
                            !fireTime.isBefore(before) && !fireTime.isAfter(after),
                            "an unset runAt must thread an approximately-now fireTime, got " + fireTime);
                    ctx.completeNow();
                } catch (Throwable t) {
                    ctx.failNow(t);
                }
            });
        }

        @Test
        @DisplayName("enqueue surfaces a DurableEncodeRejectedException from the encode path as a failed future, "
                + "not an uncaught exception")
        void enqueueSurfacesDurableEncodeRejectedExceptionAsFailedFuture(Vertx vertx, VertxTestContext ctx) {
            DefaultContextHolder holder = new DefaultContextHolder();
            AtomicReference<DurableEncodeContext> observed = new AtomicReference<>();
            DurableContextPropagator propagator = propagatorWithFakeEncoder(holder, observed, true);
            DelayedJobService svc = new DelayedJobService(
                    repository, pgRepository, registrar, propagator, new DelayedJobExceptionMapper());
            DelayedJob job = DelayedJob.builder().handler("my-handler").build();

            runOnDuplicated(vertx, v -> {
                try (ContextHolder.Scope scope = holder.bind(FakeCtx.class, new FakeCtx())) {
                    Future<UUID> result = svc.enqueue(job);
                    assertTrue(result.failed(), "an encode-path rejection must surface as a failed Future");
                    assertInstanceOf(DurableEncodeRejectedException.class, result.cause());
                    ctx.completeNow();
                } catch (Throwable t) {
                    ctx.failNow(t);
                }
            });
        }

        @Test
        @DisplayName("enqueue(job, client) surfaces a DurableEncodeRejectedException from the encode path as a "
                + "failed future, not an uncaught exception")
        void enqueueTransactionalSurfacesDurableEncodeRejectedExceptionAsFailedFuture(
                Vertx vertx, VertxTestContext ctx) {
            DefaultContextHolder holder = new DefaultContextHolder();
            AtomicReference<DurableEncodeContext> observed = new AtomicReference<>();
            DurableContextPropagator propagator = propagatorWithFakeEncoder(holder, observed, true);
            DelayedJobService svc = new DelayedJobService(
                    repository, pgRepository, registrar, propagator, new DelayedJobExceptionMapper());
            io.vertx.sqlclient.SqlClient client = org.mockito.Mockito.mock(io.vertx.sqlclient.SqlClient.class);
            DelayedJob job = DelayedJob.builder().handler("my-handler").build();

            runOnDuplicated(vertx, v -> {
                try (ContextHolder.Scope scope = holder.bind(FakeCtx.class, new FakeCtx())) {
                    Future<UUID> result = svc.enqueue(job, client);
                    assertTrue(result.failed(), "an encode-path rejection must surface as a failed Future");
                    assertInstanceOf(DurableEncodeRejectedException.class, result.cause());
                    ctx.completeNow();
                } catch (Throwable t) {
                    ctx.failNow(t);
                }
            });
        }
    }
}
