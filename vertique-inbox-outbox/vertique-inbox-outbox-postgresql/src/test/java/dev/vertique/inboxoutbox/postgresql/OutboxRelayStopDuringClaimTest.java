// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
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
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
 * A claim that is still in flight when the relay stops must not be delivered by the stopped relay:
 * the entries it returns go back to the pool at once, so the relay that runs next (or another node)
 * takes them.
 *
 * <p>The relay is deployed against a mocked {@link OutboxRepository} whose first claim is held open
 * until the test has undeployed the relay.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class OutboxRelayStopDuringClaimTest {

    private static final long WAIT_MS = 3_000L;
    private static final String NODE = "stopped-node";
    private static final long ENTRY_ID = 7L;

    @Mock
    OutboxRepository outboxRepository;

    Vertx vertx;
    final AtomicInteger delivered = new AtomicInteger();

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        when(outboxRepository.markUnresolvable(anyLong(), anyString(), any())).thenReturn(Future.succeededFuture(true));
    }

    @AfterEach
    void tearDown() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("entries claimed after stop are released without being delivered")
    void claimSettlingAfterStopIsReleasedNotDelivered() throws Exception {
        Promise<List<OutboxRecord>> heldClaim = Promise.promise();
        when(outboxRepository.claimBatch(anyInt(), anyString(), any()))
                .thenReturn(heldClaim.future(), Future.succeededFuture(List.of()));
        String deployment = deploy();
        verify(outboxRepository, timeout(WAIT_MS)).claimBatch(anyInt(), anyString(), any());

        vertx.undeploy(deployment).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        heldClaim.complete(List.of(record()));

        verify(outboxRepository, timeout(WAIT_MS)).markUnresolvable(ENTRY_ID, NODE, Duration.ZERO);
        assertEquals(0, delivered.get(), "a stopped relay must not deliver the entry it claimed after stop");
        verify(outboxRepository, never()).markPublished(anyLong(), anyString());
    }

    @Test
    @DisplayName("a release that fails after stop is contained")
    void failedReleaseAfterStopIsContained() throws Exception {
        Promise<List<OutboxRecord>> heldClaim = Promise.promise();
        when(outboxRepository.claimBatch(anyInt(), anyString(), any()))
                .thenReturn(heldClaim.future(), Future.succeededFuture(List.of()));
        when(outboxRepository.markUnresolvable(anyLong(), anyString(), any()))
                .thenReturn(Future.failedFuture(new IllegalStateException("database unavailable")));
        String deployment = deploy();
        verify(outboxRepository, timeout(WAIT_MS)).claimBatch(anyInt(), anyString(), any());

        vertx.undeploy(deployment).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        heldClaim.complete(List.of(record()));

        verify(outboxRepository, timeout(WAIT_MS)).markUnresolvable(ENTRY_ID, NODE, Duration.ZERO);
        assertEquals(0, delivered.get());
    }

    private String deploy() throws Exception {
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
                delivered.incrementAndGet();
                return Future.succeededFuture(OutboxPublishResult.success());
            }
        };
        Map<DestinationType, OutboxDestinationHandler> handlers = Map.of(DestinationType.SERVICE, handler);
        OutboxRelayConfig config = OutboxRelayConfig.builder()
                .pollingIntervalMs(20L)
                .batchSize(4)
                .strategy(RelayStrategy.POLLING)
                .build();
        OutboxRelay relay = new OutboxRelay(
                config, outboxRepository, handlers, OutboxRelay.deriveCapabilities(handlers), null, NODE, List.of());
        return vertx.deployVerticle(relay)
                .toCompletionStage()
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
    }

    private static OutboxRecord record() {
        return new OutboxRecord(
                ENTRY_ID,
                UUID.randomUUID(),
                "Order",
                "order-1",
                "order.placed",
                "my-service",
                DestinationType.SERVICE,
                new JsonObject().put("orderId", ENTRY_ID),
                Map.of(),
                OutboxMetadata.empty(),
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
