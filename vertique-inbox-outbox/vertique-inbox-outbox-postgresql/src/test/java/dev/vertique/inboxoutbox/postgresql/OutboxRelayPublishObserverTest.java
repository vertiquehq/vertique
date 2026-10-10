// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.inboxoutbox.ClaimScope;
import dev.vertique.inboxoutbox.DestinationType;
import dev.vertique.inboxoutbox.OutboxDestinationHandler;
import dev.vertique.inboxoutbox.OutboxEntryDisposition;
import dev.vertique.inboxoutbox.OutboxEntryState;
import dev.vertique.inboxoutbox.OutboxEnvelope;
import dev.vertique.inboxoutbox.OutboxMetadata;
import dev.vertique.inboxoutbox.OutboxPublishCompletedEvent;
import dev.vertique.inboxoutbox.OutboxPublishObserver;
import dev.vertique.inboxoutbox.OutboxPublishOutcome;
import dev.vertique.inboxoutbox.OutboxPublishResult;
import dev.vertique.inboxoutbox.OutboxRecord;
import dev.vertique.inboxoutbox.OutboxRelayConfig;
import dev.vertique.inboxoutbox.OutboxRepository;
import dev.vertique.inboxoutbox.RelayStrategy;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
 * Tests that the deployed {@link OutboxRelay} notifies every {@link OutboxPublishObserver} exactly
 * once per publish attempt, after the repository call that records the attempt has settled, and that
 * an observer can change neither the state transition nor the relay loop.
 *
 * <p>The relay is deployed as a real verticle against a mocked {@link OutboxRepository}, so every
 * attempt runs through the poll loop and the relay's own record processing. The first claim returns
 * one entry, every later claim returns none. A released in-flight slot and a live poll loop are both
 * observed through {@link OutboxRepository#claimBatch}: its first argument is
 * {@code batchSize - inFlight}, so a later claim asking for the full batch size proves that polling
 * continued and that no slot is still held.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class OutboxRelayPublishObserverTest {

    private static final int BATCH_SIZE = 4;
    private static final long WAIT_MS = 3_000L;
    private static final String NODE = "test-node";
    private static final long ENTRY_ID = 1L;
    private static final Duration UNRESOLVABLE_DEFERRAL = Duration.ofSeconds(60);

    @Mock
    OutboxRepository outboxRepository;

    Vertx vertx;
    Instant testStart;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        testStart = Instant.now();
        when(outboxRepository.markPublished(anyLong(), anyString())).thenReturn(Future.succeededFuture(true));
        when(outboxRepository.markRetry(anyLong(), anyString(), anyInt(), any(), any(), any()))
                .thenReturn(Future.succeededFuture(true));
        when(outboxRepository.markDeadLetter(anyLong(), anyString(), any(), any()))
                .thenReturn(Future.succeededFuture(true));
        when(outboxRepository.markUnresolvable(anyLong(), anyString(), any())).thenReturn(Future.succeededFuture(true));
    }

    @AfterEach
    void tearDown() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    // --- One notification per attempt, for every way an attempt can end ---

    @Nested
    @DisplayName("one notification per attempt")
    class OneNotificationPerAttempt {

        @Test
        @DisplayName("success: SUCCESS / PUBLISHED, recorded, no next attempt")
        void success() throws Exception {
            RecordingObserver observer = new RecordingObserver();
            claim(record(DestinationType.SERVICE, 0, 3));
            deploy(envelope -> Future.succeededFuture(OutboxPublishResult.success()), observer);

            Notification n = awaitTheOnlyNotification(observer);

            assertAttemptFacts(n, OutboxPublishOutcome.SUCCESS, OutboxEntryDisposition.PUBLISHED, 0, 3);
            assertTrue(n.event().dispositionRecorded(), "the repository confirmed the transition");
            assertNull(n.event().nextAttemptAt(), "a published entry has no next attempt");
            assertNull(n.event().errorType(), "a successful attempt has no error type");
            assertEquals(DestinationType.SERVICE, n.event().destinationType());
            assertEquals("my-service", n.event().destination());
            assertFalse(n.event().elapsed().isNegative(), "elapsed must not be negative");
            verify(outboxRepository).markPublished(ENTRY_ID, NODE);
        }

        @Test
        @DisplayName("retryable failure with attempts left: RETRYABLE_FAILURE / RETRY_SCHEDULED with the backoff time")
        void retryScheduled() throws Exception {
            RecordingObserver observer = new RecordingObserver();
            claim(record(DestinationType.SERVICE, 0, 3));
            deploy(
                    envelope -> Future.succeededFuture(
                            OutboxPublishResult.retryable("upstream timeout", new IllegalStateException("timeout"))),
                    observer);

            Notification n = awaitTheOnlyNotification(observer);

            assertAttemptFacts(n, OutboxPublishOutcome.RETRYABLE_FAILURE, OutboxEntryDisposition.RETRY_SCHEDULED, 0, 3);
            assertTrue(n.event().dispositionRecorded());
            assertEquals(
                    markRetryAvailableAt(), n.event().nextAttemptAt(), "nextAttemptAt is the time given to markRetry");
            assertEquals(IllegalStateException.class.getName(), n.event().errorType());
        }

        @Test
        @DisplayName("retryable failure on the last attempt: RETRYABLE_FAILURE / DEAD_LETTERED")
        void deadLetterOnExhaustion() throws Exception {
            RecordingObserver observer = new RecordingObserver();
            claim(record(DestinationType.SERVICE, 2, 3));
            deploy(
                    envelope -> Future.succeededFuture(
                            OutboxPublishResult.retryable("still failing", new IllegalStateException("down"))),
                    observer);

            Notification n = awaitTheOnlyNotification(observer);

            assertAttemptFacts(n, OutboxPublishOutcome.RETRYABLE_FAILURE, OutboxEntryDisposition.DEAD_LETTERED, 2, 3);
            assertTrue(n.event().dispositionRecorded());
            assertNull(n.event().nextAttemptAt(), "a dead-lettered entry has no next attempt");
            assertEquals(IllegalStateException.class.getName(), n.event().errorType());
            verify(outboxRepository)
                    .markDeadLetter(ENTRY_ID, NODE, "still failing", IllegalStateException.class.getName());
            verify(outboxRepository, never()).markRetry(anyLong(), anyString(), anyInt(), any(), any(), any());
        }

        @Test
        @DisplayName("permanent failure: PERMANENT_FAILURE / DEAD_LETTERED")
        void permanentFailure() throws Exception {
            RecordingObserver observer = new RecordingObserver();
            claim(record(DestinationType.SERVICE, 0, 3));
            deploy(
                    envelope -> Future.succeededFuture(
                            OutboxPublishResult.permanent("bad payload", new IllegalArgumentException("bad"))),
                    observer);

            Notification n = awaitTheOnlyNotification(observer);

            assertAttemptFacts(n, OutboxPublishOutcome.PERMANENT_FAILURE, OutboxEntryDisposition.DEAD_LETTERED, 0, 3);
            assertTrue(n.event().dispositionRecorded());
            assertNull(n.event().nextAttemptAt());
            assertEquals(IllegalArgumentException.class.getName(), n.event().errorType());
            verify(outboxRepository)
                    .markDeadLetter(ENTRY_ID, NODE, "bad payload", IllegalArgumentException.class.getName());
        }

        @Test
        @DisplayName("unresolvable handler result: UNRESOLVABLE / DEFERRED with the deferral time")
        void unresolvableHandlerResult() throws Exception {
            RecordingObserver observer = new RecordingObserver();
            claim(record(DestinationType.SERVICE, 1, 3));
            deploy(
                    envelope -> Future.succeededFuture(OutboxPublishResult.unresolvable("target not deployed")),
                    observer);

            Notification n = awaitTheOnlyNotification(observer);

            assertAttemptFacts(n, OutboxPublishOutcome.UNRESOLVABLE, OutboxEntryDisposition.DEFERRED, 1, 3);
            assertTrue(n.event().dispositionRecorded());
            assertDeferredNextAttempt(n);
            assertNull(n.event().errorType());
            verify(outboxRepository).markUnresolvable(ENTRY_ID, NODE, UNRESOLVABLE_DEFERRAL);
        }

        @Test
        @DisplayName("no handler for the destination type: UNRESOLVABLE / DEFERRED, an envelope and zero elapsed")
        void noHandlerForTheType() throws Exception {
            RecordingObserver observer = new RecordingObserver();
            claim(record(DestinationType.KAFKA, 0, 3));
            // Only a SERVICE handler is registered; the claimed row is of type KAFKA.
            deploy(envelope -> fail("the SERVICE handler must not be called for a KAFKA entry"), observer);

            Notification n = awaitTheOnlyNotification(observer);

            assertAttemptFacts(n, OutboxPublishOutcome.UNRESOLVABLE, OutboxEntryDisposition.DEFERRED, 0, 3);
            assertTrue(n.event().dispositionRecorded());
            assertDeferredNextAttempt(n);
            assertNull(n.event().errorType());
            assertEquals(DestinationType.KAFKA, n.event().destinationType());
            assertEquals(Duration.ZERO, n.event().elapsed(), "no publish call was made");
            verify(outboxRepository).markUnresolvable(ENTRY_ID, NODE, UNRESOLVABLE_DEFERRAL);
        }

        @Test
        @DisplayName("handler throws synchronously: RETRYABLE_FAILURE / RETRY_SCHEDULED")
        void synchronousHandlerThrow() throws Exception {
            RecordingObserver observer = new RecordingObserver();
            claim(record(DestinationType.SERVICE, 0, 3));
            deploy(
                    envelope -> {
                        throw new IllegalStateException("handler broke");
                    },
                    observer);

            Notification n = awaitTheOnlyNotification(observer);

            assertAttemptFacts(n, OutboxPublishOutcome.RETRYABLE_FAILURE, OutboxEntryDisposition.RETRY_SCHEDULED, 0, 3);
            assertTrue(n.event().dispositionRecorded());
            assertEquals(markRetryAvailableAt(), n.event().nextAttemptAt());
            assertEquals(IllegalStateException.class.getName(), n.event().errorType());
        }

        @Test
        @DisplayName("handler future fails: RETRYABLE_FAILURE / RETRY_SCHEDULED")
        void failedHandlerFuture() throws Exception {
            RecordingObserver observer = new RecordingObserver();
            claim(record(DestinationType.SERVICE, 0, 3));
            deploy(envelope -> Future.failedFuture(new java.io.IOException("connection reset")), observer);

            Notification n = awaitTheOnlyNotification(observer);

            assertAttemptFacts(n, OutboxPublishOutcome.RETRYABLE_FAILURE, OutboxEntryDisposition.RETRY_SCHEDULED, 0, 3);
            assertTrue(n.event().dispositionRecorded());
            assertEquals(markRetryAvailableAt(), n.event().nextAttemptAt());
            assertEquals(java.io.IOException.class.getName(), n.event().errorType());
        }

        @Test
        @DisplayName("handler throws an Error: RETRYABLE_FAILURE / RETRY_SCHEDULED")
        void errorFromPublish() throws Exception {
            RecordingObserver observer = new RecordingObserver();
            claim(record(DestinationType.SERVICE, 0, 3));
            deploy(
                    envelope -> {
                        throw new NoClassDefFoundError("com/example/Missing");
                    },
                    observer);

            Notification n = awaitTheOnlyNotification(observer);

            assertAttemptFacts(n, OutboxPublishOutcome.RETRYABLE_FAILURE, OutboxEntryDisposition.RETRY_SCHEDULED, 0, 3);
            assertTrue(n.event().dispositionRecorded());
            assertEquals(markRetryAvailableAt(), n.event().nextAttemptAt());
            assertEquals(NoClassDefFoundError.class.getName(), n.event().errorType());
        }

        @Test
        @DisplayName("handler returns null instead of a future: RETRYABLE_FAILURE / RETRY_SCHEDULED")
        void nullHandlerFuture() throws Exception {
            RecordingObserver observer = new RecordingObserver();
            claim(record(DestinationType.SERVICE, 0, 3));
            deploy(envelope -> null, observer);

            Notification n = awaitTheOnlyNotification(observer);

            assertAttemptFacts(n, OutboxPublishOutcome.RETRYABLE_FAILURE, OutboxEntryDisposition.RETRY_SCHEDULED, 0, 3);
            assertTrue(n.event().dispositionRecorded());
            assertEquals(markRetryAvailableAt(), n.event().nextAttemptAt());
            assertNotNull(n.event().errorType(), "a null future is reported with an error type");
        }

        @Test
        @DisplayName("handler future succeeds with a null result: RETRYABLE_FAILURE / RETRY_SCHEDULED")
        void nullHandlerResult() throws Exception {
            RecordingObserver observer = new RecordingObserver();
            claim(record(DestinationType.SERVICE, 0, 3));
            deploy(envelope -> Future.succeededFuture(null), observer);

            Notification n = awaitTheOnlyNotification(observer);

            assertAttemptFacts(n, OutboxPublishOutcome.RETRYABLE_FAILURE, OutboxEntryDisposition.RETRY_SCHEDULED, 0, 3);
            assertTrue(n.event().dispositionRecorded());
            assertEquals(markRetryAvailableAt(), n.event().nextAttemptAt());
            assertNotNull(n.event().errorType(), "a null result is reported with an error type");
            verify(outboxRepository, never()).markPublished(anyLong(), anyString());
        }

        @Test
        @DisplayName("repository call throws synchronously: notified with dispositionRecorded false")
        void synchronousThrowFromTheRepositoryCall() throws Exception {
            RecordingObserver observer = new RecordingObserver();
            when(outboxRepository.markPublished(ENTRY_ID, NODE)).thenThrow(new IllegalStateException("pool closed"));
            claim(record(DestinationType.SERVICE, 0, 3));
            deploy(envelope -> Future.succeededFuture(OutboxPublishResult.success()), observer);

            Notification n = awaitTheOnlyNotification(observer);

            assertAttemptFacts(n, OutboxPublishOutcome.SUCCESS, OutboxEntryDisposition.PUBLISHED, 0, 3);
            assertFalse(n.event().dispositionRecorded(), "the state change was attempted and threw");
            assertNull(n.event().nextAttemptAt());
            verify(outboxRepository, never()).markRetry(anyLong(), anyString(), anyInt(), any(), any(), any());
        }
    }

    // --- dispositionRecorded and notification timing ---

    @Nested
    @DisplayName("the recorded flag and the notification time")
    class RecordedFlagAndTiming {

        @Test
        @DisplayName("repository future fails: dispositionRecorded false")
        void repositoryFutureFails() throws Exception {
            RecordingObserver observer = new RecordingObserver();
            when(outboxRepository.markPublished(ENTRY_ID, NODE))
                    .thenReturn(Future.failedFuture(new IllegalStateException("connection lost")));
            claim(record(DestinationType.SERVICE, 0, 3));
            deploy(envelope -> Future.succeededFuture(OutboxPublishResult.success()), observer);

            Notification n = awaitTheOnlyNotification(observer);

            assertAttemptFacts(n, OutboxPublishOutcome.SUCCESS, OutboxEntryDisposition.PUBLISHED, 0, 3);
            assertFalse(n.event().dispositionRecorded(), "a failed repository call did not record the transition");
        }

        @Test
        @DisplayName("repository reports the row as not owned: dispositionRecorded false")
        void repositoryReturnsFalse() throws Exception {
            RecordingObserver observer = new RecordingObserver();
            when(outboxRepository.markRetry(anyLong(), anyString(), anyInt(), any(), any(), any()))
                    .thenReturn(Future.succeededFuture(false));
            claim(record(DestinationType.SERVICE, 0, 3));
            deploy(
                    envelope -> Future.succeededFuture(OutboxPublishResult.retryable("upstream timeout", null)),
                    observer);

            Notification n = awaitTheOnlyNotification(observer);

            assertAttemptFacts(n, OutboxPublishOutcome.RETRYABLE_FAILURE, OutboxEntryDisposition.RETRY_SCHEDULED, 0, 3);
            assertFalse(n.event().dispositionRecorded(), "the row was not owned, so nothing was recorded");
            assertEquals(markRetryAvailableAt(), n.event().nextAttemptAt());
            assertNull(n.event().errorType(), "a failure without a cause has no error type");
        }

        @Test
        @DisplayName("no notification before the repository future settles; slot and polling do not wait for it")
        void notifiedOnlyAfterTheRepositoryFutureSettles() throws Exception {
            RecordingObserver observer = new RecordingObserver();
            Promise<Boolean> markPublished = Promise.promise();
            when(outboxRepository.markPublished(ENTRY_ID, NODE)).thenReturn(markPublished.future());
            claim(record(DestinationType.SERVICE, 0, 3));
            deploy(envelope -> Future.succeededFuture(OutboxPublishResult.success()), observer);

            // The repository call was made and has not settled. The slot is free again and polling
            // goes on: three claims asked for the full batch size (the first one, and two after it).
            verify(outboxRepository, timeout(WAIT_MS)).markPublished(ENTRY_ID, NODE);
            verify(outboxRepository, timeout(WAIT_MS).atLeast(3)).claimBatch(eq(BATCH_SIZE), eq(NODE), any());
            assertTrue(observer.notifications.isEmpty(), "no notification before the repository call settles");

            markPublished.complete(true);

            Notification n = awaitTheOnlyNotification(observer);
            assertAttemptFacts(n, OutboxPublishOutcome.SUCCESS, OutboxEntryDisposition.PUBLISHED, 0, 3);
            assertTrue(n.event().dispositionRecorded());
        }
    }

    // --- Observer isolation and order ---

    @Nested
    @DisplayName("observer isolation and order")
    class IsolationAndOrder {

        @Test
        @DisplayName("observer throws RuntimeException: transition, later observers and polling unaffected")
        void runtimeExceptionIsIsolated() throws Exception {
            assertIsolated(() -> new IllegalStateException("observer broke"));
        }

        @Test
        @DisplayName("observer throws AssertionError: transition, later observers and polling unaffected")
        void assertionErrorIsIsolated() throws Exception {
            assertIsolated(() -> new AssertionError("observer invariant broken"));
        }

        @Test
        @DisplayName("observer throws LinkageError: transition, later observers and polling unaffected")
        void linkageErrorIsIsolated() throws Exception {
            assertIsolated(() -> new NoClassDefFoundError("com/example/Missing"));
        }

        private void assertIsolated(Supplier<Throwable> failure) throws Exception {
            ThrowingObserver first = new ThrowingObserver(failure);
            RecordingObserver second = new RecordingObserver(1, "second");
            claim(record(DestinationType.SERVICE, 0, 3));
            deploy(envelope -> Future.succeededFuture(OutboxPublishResult.success()), first, second);

            Notification n = awaitTheOnlyNotification(second);

            assertEquals(1, first.calls.size(), "the throwing observer was called once");
            assertAttemptFacts(n, OutboxPublishOutcome.SUCCESS, OutboxEntryDisposition.PUBLISHED, 0, 3);
            assertTrue(n.event().dispositionRecorded());
            verify(outboxRepository).markPublished(ENTRY_ID, NODE);
            verify(outboxRepository, never()).markRetry(anyLong(), anyString(), anyInt(), any(), any(), any());
            verify(outboxRepository, never()).markDeadLetter(anyLong(), anyString(), any(), any());
            verify(outboxRepository, never()).markUnresolvable(anyLong(), anyString(), any());
        }

        @Test
        @DisplayName("observers run in OrderedExtension order: priority, then order key")
        void observersRunInOrder() throws Exception {
            List<String> calls = new CopyOnWriteArrayList<>();
            RecordingObserver last = new RecordingObserver(2, "a", calls);
            RecordingObserver first = new RecordingObserver(0, "z", calls);
            RecordingObserver secondB = new RecordingObserver(1, "b", calls);
            RecordingObserver secondA = new RecordingObserver(1, "a", calls);
            claim(record(DestinationType.SERVICE, 0, 3));
            deploy(envelope -> Future.succeededFuture(OutboxPublishResult.success()), last, secondB, first, secondA);

            awaitTheOnlyNotification(last);

            assertEquals(List.of("0:z", "1:a", "1:b", "2:a"), calls);
            // Every observer is given the same event and the same envelope.
            assertSame(
                    first.notifications.get(0).event(),
                    last.notifications.get(0).event());
            assertSame(
                    first.notifications.get(0).envelope(),
                    last.notifications.get(0).envelope());
        }
    }

    // --- Assertions ---

    /**
     * Asserts the facts every notification carries, whatever the outcome: the classified outcome, the
     * relay's disposition, the attempt numbers, the entry id, a completion time and the relay-built
     * envelope.
     */
    private void assertAttemptFacts(
            Notification n,
            OutboxPublishOutcome outcome,
            OutboxEntryDisposition disposition,
            int attempt,
            int maxAttempts) {
        OutboxPublishCompletedEvent event = n.event();
        assertEquals(outcome, event.outcome(), "outcome");
        assertEquals(disposition, event.disposition(), "disposition");
        assertEquals(attempt, event.attempt(), "attempt");
        assertEquals(maxAttempts, event.maxAttempts(), "maxAttempts");
        assertEquals(String.valueOf(ENTRY_ID), event.entryId(), "entryId");
        assertNotNull(event.elapsed(), "elapsed");
        assertFalse(event.completedAt().isBefore(testStart), "completedAt is not before the test started");
        assertFalse(event.completedAt().isAfter(Instant.now()), "completedAt is not in the future");

        OutboxEnvelope envelope = n.envelope();
        assertNotNull(envelope, "the observer is given the relay-built envelope");
        assertEquals(ENTRY_ID, envelope.entryId());
        assertEquals(attempt, envelope.attempt(), "the event's attempt equals the envelope's");
        assertEquals(event.destination(), envelope.destination(), "the event's destination is the envelope's");
        assertTrue(envelope.metadata().delivery().outbox().isPresent(), "the envelope carries the relay control");
    }

    private void assertDeferredNextAttempt(Notification n) {
        Instant next = n.event().nextAttemptAt();
        assertNotNull(next, "a deferred entry has a next attempt time");
        assertFalse(next.isBefore(testStart.plus(UNRESOLVABLE_DEFERRAL)), "not before start + the deferral");
        assertFalse(next.isAfter(n.event().completedAt().plus(UNRESOLVABLE_DEFERRAL)), "not after now + the deferral");
    }

    /**
     * Waits for the observer's notification, then for the proof that the slot was released and the
     * poll loop went on, and asserts that the observer was notified exactly once.
     */
    private Notification awaitTheOnlyNotification(RecordingObserver observer) throws Exception {
        await(() -> !observer.notifications.isEmpty(), "the observer was not notified");
        verify(outboxRepository, timeout(WAIT_MS).atLeast(2)).claimBatch(eq(BATCH_SIZE), eq(NODE), any());
        // Two more polls: a second notification for the same attempt would have arrived by now.
        int claims = claimCount();
        await(() -> claimCount() >= claims + 2, "the poll loop stopped");
        assertEquals(1, observer.notifications.size(), "exactly one notification per attempt");
        return observer.notifications.get(0);
    }

    private int claimCount() {
        return (int) org.mockito.Mockito.mockingDetails(outboxRepository).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("claimBatch"))
                .count();
    }

    private Instant markRetryAvailableAt() {
        ArgumentCaptor<Instant> availableAt = ArgumentCaptor.forClass(Instant.class);
        verify(outboxRepository).markRetry(eq(ENTRY_ID), eq(NODE), anyInt(), availableAt.capture(), any(), any());
        return availableAt.getValue();
    }

    private static void await(BooleanSupplier condition, String failureMessage) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MS);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail(failureMessage);
            }
            Thread.sleep(5);
        }
    }

    // --- Helpers ---

    private void claim(OutboxRecord record) {
        when(outboxRepository.claimBatch(anyInt(), anyString(), any()))
                .thenReturn(Future.succeededFuture(List.of(record)), Future.succeededFuture(List.of()));
    }

    private void deploy(
            Function<OutboxEnvelope, Future<OutboxPublishResult>> publish, OutboxPublishObserver... observers)
            throws Exception {
        OutboxDestinationHandler handler = new OutboxDestinationHandler() {
            @Override
            public DestinationType destinationType() {
                return DestinationType.SERVICE;
            }

            @Override
            public ClaimScope claimScope() {
                return ClaimScope.all();
            }

            @Override
            public Future<OutboxPublishResult> publish(OutboxEnvelope envelope) {
                return publish.apply(envelope);
            }
        };
        Map<DestinationType, OutboxDestinationHandler> handlers = Map.of(DestinationType.SERVICE, handler);
        OutboxRelayConfig config = OutboxRelayConfig.builder()
                .pollingIntervalMs(20L)
                .batchSize(BATCH_SIZE)
                .leaseTimeoutMs(30_000L)
                .maxAttempts(3)
                .backoffBaseDelayMs(1_000L)
                .backoffMaxDelayMs(60_000L)
                .strategy(RelayStrategy.POLLING)
                .build();
        OutboxRelay relay = new OutboxRelay(
                config,
                outboxRepository,
                handlers,
                OutboxRelay.deriveCapabilities(handlers),
                null,
                NODE,
                List.of(observers));
        vertx.deployVerticle(relay).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private static OutboxRecord record(DestinationType destinationType, int attempt, int maxAttempts) {
        return new OutboxRecord(
                ENTRY_ID,
                UUID.randomUUID(),
                "Order",
                "order-1",
                "order.placed",
                "my-service",
                destinationType,
                new JsonObject().put("orderId", ENTRY_ID),
                Map.of(),
                OutboxMetadata.empty(),
                null,
                Instant.now(),
                OutboxEntryState.PROCESSING,
                attempt,
                maxAttempts,
                Instant.now(),
                NODE,
                null,
                null,
                null,
                Instant.now(),
                Instant.now());
    }

    /** One call of {@link OutboxPublishObserver#onPublishCompleted}. */
    private record Notification(OutboxPublishCompletedEvent event, OutboxEnvelope envelope) {}

    /** Observer that records every call, with a configurable place in the order. */
    private static final class RecordingObserver implements OutboxPublishObserver {

        final List<Notification> notifications = new CopyOnWriteArrayList<>();
        private final int priority;
        private final String orderKey;
        private final List<String> sharedCalls;

        RecordingObserver() {
            this(0, "recording");
        }

        RecordingObserver(int priority, String orderKey) {
            this(priority, orderKey, new CopyOnWriteArrayList<>());
        }

        RecordingObserver(int priority, String orderKey, List<String> sharedCalls) {
            this.priority = priority;
            this.orderKey = orderKey;
            this.sharedCalls = sharedCalls;
        }

        @Override
        public int priority() {
            return priority;
        }

        @Override
        public String orderKey() {
            return orderKey;
        }

        @Override
        public void onPublishCompleted(OutboxPublishCompletedEvent event, OutboxEnvelope envelope) {
            sharedCalls.add(priority + ":" + orderKey);
            notifications.add(new Notification(event, envelope));
        }
    }

    /** Observer that runs first and always throws. */
    private static final class ThrowingObserver implements OutboxPublishObserver {

        final List<OutboxPublishCompletedEvent> calls = new CopyOnWriteArrayList<>();
        private final Supplier<Throwable> failure;

        ThrowingObserver(Supplier<Throwable> failure) {
            this.failure = failure;
        }

        @Override
        public int priority() {
            return -1;
        }

        @Override
        public void onPublishCompleted(OutboxPublishCompletedEvent event, OutboxEnvelope envelope) {
            calls.add(event);
            Throwable thrown = failure.get();
            if (thrown instanceof Error error) {
                throw error;
            }
            throw (RuntimeException) thrown;
        }
    }
}
