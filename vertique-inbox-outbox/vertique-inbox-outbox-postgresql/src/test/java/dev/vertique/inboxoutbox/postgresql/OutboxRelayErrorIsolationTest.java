// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.postgresql;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.inboxoutbox.ClaimScope;
import dev.vertique.inboxoutbox.DestinationType;
import dev.vertique.inboxoutbox.OutboxDestinationHandler;
import dev.vertique.inboxoutbox.OutboxEntryState;
import dev.vertique.inboxoutbox.OutboxEnvelope;
import dev.vertique.inboxoutbox.OutboxMetadata;
import dev.vertique.inboxoutbox.OutboxPublishResult;
import dev.vertique.inboxoutbox.OutboxRecord;
import dev.vertique.inboxoutbox.OutboxRelayConfig;
import dev.vertique.inboxoutbox.OutboxRepository;
import dev.vertique.inboxoutbox.RelayStrategy;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Tests that a misbehaving destination handler, a throwing result-recording step, a record whose
 * envelope cannot be built or a misbehaving claim cannot stop the deployed {@link OutboxRelay}: the
 * failing entry is handled as a failed attempt, the rest of the claimed batch is still delivered,
 * the in-flight slot is released and the poll loop keeps running.
 *
 * <p>The relay is deployed as a real verticle against a mocked {@link OutboxRepository}. The first
 * claim returns two entries, every later claim returns none. A released in-flight slot and a live
 * poll loop are both observed through {@link OutboxRepository#claimBatch}: its first argument is
 * {@code batchSize - inFlight}, so a later claim asking for the full batch size proves that polling
 * continued and that no slot is still held.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class OutboxRelayErrorIsolationTest {

    private static final int BATCH_SIZE = 4;
    private static final long WAIT_MS = 3_000L;
    private static final String NODE = "test-node";

    @Mock
    OutboxRepository outboxRepository;

    Vertx vertx;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        when(outboxRepository.claimBatch(anyInt(), anyString(), any()))
                .thenReturn(Future.succeededFuture(List.of(record(1L), record(2L))), Future.succeededFuture(List.of()));
        when(outboxRepository.markPublished(anyLong(), anyString())).thenReturn(Future.succeededFuture(true));
        when(outboxRepository.markRetry(anyLong(), anyString(), anyInt(), any(), any(), any()))
                .thenReturn(Future.succeededFuture(true));
    }

    @AfterEach
    void tearDown() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("handler throws AssertionError: failed attempt, rest of batch delivered, polling continues")
    void handlerThrowingAssertionErrorIsAFailedAttempt() throws Exception {
        deployRelayWith(envelope -> {
            if (envelope.entryId() == 1L) {
                throw new AssertionError("handler invariant broken");
            }
            return Future.succeededFuture(OutboxPublishResult.success());
        });

        verify(outboxRepository, timeout(WAIT_MS))
                .markRetry(
                        eq(1L),
                        eq(NODE),
                        eq(1),
                        any(Instant.class),
                        contains("handler invariant broken"),
                        eq(AssertionError.class.getName()));
        verify(outboxRepository, timeout(WAIT_MS)).markPublished(2L, NODE);
        verify(outboxRepository, timeout(WAIT_MS).atLeast(2)).claimBatch(eq(BATCH_SIZE), eq(NODE), any());
    }

    @Test
    @DisplayName("handler throws LinkageError: failed attempt, rest of batch delivered, polling continues")
    void handlerThrowingLinkageErrorIsAFailedAttempt() throws Exception {
        deployRelayWith(envelope -> {
            if (envelope.entryId() == 1L) {
                throw new NoClassDefFoundError("com/example/Missing");
            }
            return Future.succeededFuture(OutboxPublishResult.success());
        });

        verify(outboxRepository, timeout(WAIT_MS))
                .markRetry(
                        eq(1L),
                        eq(NODE),
                        eq(1),
                        any(Instant.class),
                        anyString(),
                        eq(NoClassDefFoundError.class.getName()));
        verify(outboxRepository, timeout(WAIT_MS)).markPublished(2L, NODE);
        verify(outboxRepository, timeout(WAIT_MS).atLeast(2)).claimBatch(eq(BATCH_SIZE), eq(NODE), any());
    }

    @Test
    @DisplayName("handler returns null instead of a future: failed attempt, rest of batch delivered")
    void handlerReturningNullFutureIsAFailedAttempt() throws Exception {
        deployRelayWith(
                envelope -> envelope.entryId() == 1L ? null : Future.succeededFuture(OutboxPublishResult.success()));

        verify(outboxRepository, timeout(WAIT_MS))
                .markRetry(eq(1L), eq(NODE), eq(1), any(Instant.class), anyString(), any());
        verify(outboxRepository, timeout(WAIT_MS)).markPublished(2L, NODE);
        verify(outboxRepository, timeout(WAIT_MS).atLeast(2)).claimBatch(eq(BATCH_SIZE), eq(NODE), any());
    }

    @Test
    @DisplayName("handler future completes with a null result: rest of batch delivered, slot released")
    void handlerCompletingWithNullResultDoesNotStopTheBatch() throws Exception {
        deployRelayWith(envelope -> envelope.entryId() == 1L
                ? Future.succeededFuture(null)
                : Future.succeededFuture(OutboxPublishResult.success()));

        verify(outboxRepository, timeout(WAIT_MS)).markPublished(2L, NODE);
        verify(outboxRepository, timeout(WAIT_MS).atLeast(2)).claimBatch(eq(BATCH_SIZE), eq(NODE), any());
    }

    @Test
    @DisplayName("recording an outcome throws: rest of batch delivered, slot released, polling continues")
    void throwWhileRecordingAnOutcomeDoesNotStopTheBatch() throws Exception {
        when(outboxRepository.markPublished(1L, NODE)).thenThrow(new IllegalStateException("pool closed"));
        deployRelayWith(envelope -> Future.succeededFuture(OutboxPublishResult.success()));

        verify(outboxRepository, timeout(WAIT_MS)).markPublished(1L, NODE);
        verify(outboxRepository, timeout(WAIT_MS)).markPublished(2L, NODE);
        verify(outboxRepository, timeout(WAIT_MS).atLeast(2)).claimBatch(eq(BATCH_SIZE), eq(NODE), any());
        verify(outboxRepository, never()).markRetry(anyLong(), anyString(), anyInt(), any(), any(), any());
    }

    @Test
    @DisplayName("handler throws StackOverflowError: failed attempt, rest of batch delivered, polling continues")
    void handlerThrowingStackOverflowErrorIsAFailedAttempt() throws Exception {
        deployRelayWith(envelope -> {
            if (envelope.entryId() == 1L) {
                throw new StackOverflowError("handler recursed too deep");
            }
            return Future.succeededFuture(OutboxPublishResult.success());
        });

        verify(outboxRepository, timeout(WAIT_MS))
                .markRetry(
                        eq(1L),
                        eq(NODE),
                        eq(1),
                        any(Instant.class),
                        anyString(),
                        eq(StackOverflowError.class.getName()));
        verify(outboxRepository, timeout(WAIT_MS)).markPublished(2L, NODE);
        verify(outboxRepository, timeout(WAIT_MS).atLeast(2)).claimBatch(eq(BATCH_SIZE), eq(NODE), any());
    }

    @Test
    @DisplayName("claimBatch throws synchronously: the next poll is still scheduled")
    void claimBatchThrowingSynchronouslyDoesNotStopPolling() throws Exception {
        when(outboxRepository.claimBatch(anyInt(), anyString(), any()))
                .thenThrow(new IllegalStateException("pool closed"))
                .thenReturn(Future.succeededFuture(List.of(record(1L))), Future.succeededFuture(List.of()));
        deployRelayWith(envelope -> Future.succeededFuture(OutboxPublishResult.success()));

        // The claim after the throwing one is made, and its entry is delivered.
        verify(outboxRepository, timeout(WAIT_MS)).markPublished(1L, NODE);
        verify(outboxRepository, timeout(WAIT_MS).atLeast(3)).claimBatch(eq(BATCH_SIZE), eq(NODE), any());
    }

    @Test
    @DisplayName("claimBatch completes with a null list: handled as an empty batch, the next poll is still scheduled")
    void claimBatchCompletingWithNullDoesNotStopPolling() throws Exception {
        when(outboxRepository.claimBatch(anyInt(), anyString(), any()))
                .thenReturn(
                        Future.succeededFuture(null),
                        Future.succeededFuture(List.of(record(1L))),
                        Future.succeededFuture(List.of()));
        deployRelayWith(envelope -> Future.succeededFuture(OutboxPublishResult.success()));

        verify(outboxRepository, timeout(WAIT_MS)).markPublished(1L, NODE);
        verify(outboxRepository, timeout(WAIT_MS).atLeast(3)).claimBatch(eq(BATCH_SIZE), eq(NODE), any());
    }

    @Test
    @DisplayName(
            "claimBatch returns null instead of a future: handled as a failed poll, the next poll is still scheduled")
    void claimBatchReturningNullFutureDoesNotStopPolling() throws Exception {
        when(outboxRepository.claimBatch(anyInt(), anyString(), any()))
                .thenReturn(null, Future.succeededFuture(List.of(record(1L))), Future.succeededFuture(List.of()));
        deployRelayWith(envelope -> Future.succeededFuture(OutboxPublishResult.success()));

        verify(outboxRepository, timeout(WAIT_MS)).markPublished(1L, NODE);
        verify(outboxRepository, timeout(WAIT_MS).atLeast(3)).claimBatch(eq(BATCH_SIZE), eq(NODE), any());
    }

    @Test
    @DisplayName(
            "envelope cannot be built: slot released, rest of batch delivered, polling continues, nothing recorded")
    void throwWhileBuildingTheEnvelopeDoesNotStopTheBatch() throws Exception {
        // A record without metadata makes the relay's envelope construction throw.
        when(outboxRepository.claimBatch(anyInt(), anyString(), any()))
                .thenReturn(
                        Future.succeededFuture(List.of(recordWithoutMetadata(1L), record(2L))),
                        Future.succeededFuture(List.of()));
        deployRelayWith(envelope -> Future.succeededFuture(OutboxPublishResult.success()));

        verify(outboxRepository, timeout(WAIT_MS)).markPublished(2L, NODE);
        verify(outboxRepository, timeout(WAIT_MS).atLeast(2)).claimBatch(eq(BATCH_SIZE), eq(NODE), any());
        verify(outboxRepository, never()).markPublished(1L, NODE);
        verify(outboxRepository, never()).markRetry(anyLong(), anyString(), anyInt(), any(), any(), any());
    }

    // --- Helpers ---

    private void deployRelayWith(Function<OutboxEnvelope, Future<OutboxPublishResult>> publish) throws Exception {
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
                config, outboxRepository, handlers, OutboxRelay.deriveCapabilities(handlers), null, NODE);
        vertx.deployVerticle(relay).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private static OutboxRecord record(long id) {
        return record(id, OutboxMetadata.empty());
    }

    private static OutboxRecord recordWithoutMetadata(long id) {
        return record(id, null);
    }

    private static OutboxRecord record(long id, OutboxMetadata metadata) {
        return new OutboxRecord(
                id,
                UUID.randomUUID(),
                "Order",
                "order-" + id,
                "order.placed",
                "my-service",
                DestinationType.SERVICE,
                new JsonObject().put("orderId", id),
                Map.of(),
                metadata,
                null,
                Instant.now(),
                OutboxEntryState.PROCESSING,
                0,
                3,
                Instant.now(),
                NODE,
                null,
                null,
                null,
                Instant.now(),
                Instant.now());
    }
}
