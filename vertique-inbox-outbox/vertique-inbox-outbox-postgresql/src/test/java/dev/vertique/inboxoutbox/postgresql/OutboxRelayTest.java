// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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
import dev.vertique.inboxoutbox.OutboxRelayControl;
import dev.vertique.inboxoutbox.OutboxRepository;
import dev.vertique.inboxoutbox.RelayCapabilities;
import dev.vertique.inboxoutbox.RelayStrategy;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tests for {@link OutboxRelay} — {@link OutboxRelay#handleResult} outcome routing and
 * {@link OutboxRelay#buildEnvelope} header merging. The relay is instantiated directly
 * without deploying it as a verticle; only the package-private methods under test are invoked.
 */
@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

    @Mock
    OutboxRepository outboxRepository;

    OutboxRelayConfig relayConfig;
    OutboxRelay relay;

    @BeforeEach
    void setUp() {
        relayConfig = OutboxRelayConfig.builder()
                .pollingIntervalMs(1_000L)
                .batchSize(10)
                .leaseTimeoutMs(30_000L)
                .maxAttempts(3)
                .backoffBaseDelayMs(1_000L)
                .backoffMaxDelayMs(60_000L)
                .strategy(RelayStrategy.POLLING)
                .build();

        // No handlers registered — each test can check unresolvable behaviour via the empty map.
        // Tests that need a handler create their own relay instance.
        relay = new OutboxRelay(
                relayConfig,
                outboxRepository,
                Map.of(),
                new RelayCapabilities(Map.of()),
                null, // connectOptions — never used without calling start()
                "test-node");
    }

    // --- Helper: build a minimal OutboxRecord ---

    private OutboxRecord record(long id, int attempt, int maxAttempts, Map<String, String> headers) {
        return new OutboxRecord(
                id,
                UUID.randomUUID(),
                "Order",
                "order-99",
                "order.placed",
                "my-service",
                DestinationType.SERVICE,
                new JsonObject().put("orderId", "99"),
                headers,
                OutboxMetadata.empty(),
                null,
                Instant.now(),
                OutboxEntryState.PROCESSING,
                attempt,
                maxAttempts,
                Instant.now(),
                "test-node",
                null,
                null,
                null,
                Instant.now(),
                Instant.now());
    }

    // --- handleResult tests ---

    @Nested
    class handleResult {

        @Test
        @DisplayName("Success result marks entry as published")
        void successResultMarksEntryAsPublished() {
            when(outboxRepository.markPublished(1L, "test-node")).thenReturn(Future.succeededFuture(true));

            OutboxRecord rec = record(1L, 0, 3, Map.of());
            relay.handleResult(rec, OutboxPublishResult.success());

            verify(outboxRepository).markPublished(1L, "test-node");
            verify(outboxRepository, never())
                    .markRetry(anyLong(), anyString(), any(int.class), any(), anyString(), any());
            verify(outboxRepository, never()).markDeadLetter(anyLong(), anyString(), anyString(), any());
        }

        @Test
        @DisplayName("RetryableFailure with attempts remaining marks retry")
        void retryableFailureWithAttemptsRemainingMarksRetry() {
            when(outboxRepository.markRetry(anyLong(), anyString(), any(int.class), any(), anyString(), any()))
                    .thenReturn(Future.succeededFuture(true));

            // attempt=0, maxAttempts=3 → newAttempt=1, still < maxAttempts
            OutboxRecord rec = record(2L, 0, 3, Map.of());
            relay.handleResult(rec, OutboxPublishResult.retryable("upstream timeout", null));

            verify(outboxRepository)
                    .markRetry(eq(2L), eq("test-node"), eq(1), any(Instant.class), eq("upstream timeout"), eq(null));
            verify(outboxRepository, never()).markDeadLetter(anyLong(), anyString(), anyString(), any());
        }

        @Test
        @DisplayName("RetryableFailure with exhausted attempts dead-letters")
        void retryableFailureWithExhaustedAttemptsDeadLetters() {
            when(outboxRepository.markDeadLetter(anyLong(), anyString(), anyString(), any()))
                    .thenReturn(Future.succeededFuture(true));

            // attempt=2, maxAttempts=3 → newAttempt=3 >= maxAttempts → dead-letter
            OutboxRecord rec = record(3L, 2, 3, Map.of());
            relay.handleResult(rec, OutboxPublishResult.retryable("still failing", null));

            verify(outboxRepository).markDeadLetter(eq(3L), eq("test-node"), eq("still failing"), eq(null));
            verify(outboxRepository, never())
                    .markRetry(anyLong(), anyString(), any(int.class), any(), anyString(), any());
        }

        @Test
        @DisplayName("PermanentFailure dead-letters immediately")
        void permanentFailureDeadLettersImmediately() {
            RuntimeException cause = new RuntimeException("bad payload");
            when(outboxRepository.markDeadLetter(anyLong(), anyString(), anyString(), anyString()))
                    .thenReturn(Future.succeededFuture(true));

            OutboxRecord rec = record(4L, 0, 3, Map.of());
            relay.handleResult(rec, OutboxPublishResult.permanent("validation failed", cause));

            verify(outboxRepository)
                    .markDeadLetter(
                            eq(4L),
                            eq("test-node"),
                            eq("validation failed"),
                            eq(cause.getClass().getName()));
            verify(outboxRepository, never())
                    .markRetry(anyLong(), anyString(), any(int.class), any(), anyString(), any());
            verify(outboxRepository, never()).markPublished(anyLong(), anyString());
        }

        @Test
        @DisplayName("Unresolvable returns to PENDING without consuming attempt")
        void unresolvableReturnsToPendingWithoutConsumingAttempt() {
            when(outboxRepository.markUnresolvable(anyLong(), anyString(), any(Duration.class)))
                    .thenReturn(Future.succeededFuture(true));

            OutboxRecord rec = record(5L, 0, 3, Map.of());
            relay.handleResult(rec, OutboxPublishResult.unresolvable("no handler for SERVICE"));

            verify(outboxRepository).markUnresolvable(eq(5L), eq("test-node"), any(Duration.class));
            verify(outboxRepository, never())
                    .markRetry(anyLong(), anyString(), any(int.class), any(), anyString(), any());
            verify(outboxRepository, never()).markDeadLetter(anyLong(), anyString(), anyString(), any());
            verify(outboxRepository, never()).markPublished(anyLong(), anyString());
        }
    }

    /**
     * Builds a minimal {@link OutboxDestinationHandler} test double with the given type and scope
     * and a no-op {@code publish} that always succeeds.
     */
    private static OutboxDestinationHandler stubHandler(DestinationType type, ClaimScope scope) {
        return new OutboxDestinationHandler() {
            @Override
            public DestinationType destinationType() {
                return type;
            }

            @Override
            public ClaimScope claimScope() {
                return scope;
            }

            @Override
            public Future<OutboxPublishResult> publish(OutboxEnvelope envelope) {
                return Future.succeededFuture(OutboxPublishResult.success());
            }
        };
    }

    // --- buildHandlerMap tests ---

    @Nested
    class buildHandlerMap {

        @Test
        @DisplayName("duplicate destinationType() throws IllegalStateException")
        void duplicateDestinationTypeThrows() {
            OutboxDestinationHandler handlerA = stubHandler(DestinationType.SERVICE, ClaimScope.all());
            OutboxDestinationHandler handlerB = stubHandler(DestinationType.SERVICE, ClaimScope.all());

            assertThrows(
                    IllegalStateException.class,
                    () -> OutboxRelay.buildHandlerMap(Set.of(handlerA, handlerB)),
                    "buildHandlerMap must throw IllegalStateException for duplicate destinationType");
        }
    }

    // --- deriveCapabilities tests ---

    @Nested
    class deriveCapabilities {

        /** Minimal KAFKA-typed handler that claims every row. */
        private static final OutboxDestinationHandler KAFKA_HANDLER =
                stubHandler(DestinationType.KAFKA, ClaimScope.all());

        /** Synthetic open-type handler — destination type is not one of the built-in constants. */
        private static final OutboxDestinationHandler SYNTHETIC_HANDLER =
                stubHandler(DestinationType.of("synthetic"), ClaimScope.all());

        /** Null-scope handler — claimScope() returns null, which is a programming error. */
        private static final OutboxDestinationHandler NULL_SCOPE_HANDLER =
                stubHandler(DestinationType.of("bad-type"), null);

        @Test
        @DisplayName("maps each handler's destinationType to its claimScope, including a synthetic open type")
        void mapsHandlersIncludingSyntheticOpenType() {
            Map<DestinationType, OutboxDestinationHandler> handlerMap =
                    OutboxRelay.buildHandlerMap(Set.of(KAFKA_HANDLER, SYNTHETIC_HANDLER));

            RelayCapabilities caps = OutboxRelay.deriveCapabilities(handlerMap);

            assertEquals(2, caps.byType().size(), "byType must contain one entry per handler");

            // Lookup with a DISTINCT-but-equal key proves equality-by-id keying
            ClaimScope syntheticScope = caps.byType().get(DestinationType.of("synthetic"));
            assertNotNull(syntheticScope, "synthetic type must be present in byType");
            assertInstanceOf(
                    ClaimScope.All.class, syntheticScope, "synthetic handler's claimScope must be ClaimScope.All");
        }

        @Test
        @DisplayName("throws NullPointerException naming the type when a handler returns a null claimScope")
        void throwsNullPointerExceptionNamingTypeOnNullScope() {
            Map<DestinationType, OutboxDestinationHandler> handlerMap =
                    OutboxRelay.buildHandlerMap(Set.of(NULL_SCOPE_HANDLER));

            NullPointerException ex =
                    assertThrows(NullPointerException.class, () -> OutboxRelay.deriveCapabilities(handlerMap));
            assertNotNull(ex.getMessage(), "exception message must not be null");
            // The message must identify the offending type id so the error is actionable
            assertFalse(ex.getMessage().isBlank(), "exception message must not be blank");
            assertTrue(
                    ex.getMessage()
                            .contains(NULL_SCOPE_HANDLER.destinationType().id()),
                    "exception message must contain the offending type id '"
                            + NULL_SCOPE_HANDLER.destinationType().id() + "'");
        }
    }

    // --- buildEnvelope tests ---

    @Nested
    class buildEnvelope {

        @Test
        @DisplayName("projects relay control into delivery.outbox and keeps headers app-only")
        void projectsRelayControlIntoDeliveryOutbox() {
            OutboxRecord rec = record(10L, 0, 3, Map.of("x-tenant", "acme"));

            OutboxEnvelope envelope = relay.buildEnvelope(rec);

            assertNotNull(envelope, "envelope must not be null");
            assertEquals(10L, envelope.entryId());
            assertEquals("order.placed", envelope.eventType());
            assertEquals("Order", envelope.aggregateType());
            assertEquals("order-99", envelope.aggregateId());

            // headers carry only the application entry — no framework control keys
            Map<String, String> headers = envelope.headers();
            assertEquals("acme", headers.get("x-tenant"), "application header must be preserved");
            assertFalse(headers.containsKey("x-message-id"), "x-message-id must not be in headers");
            assertFalse(headers.containsKey("eventType"), "eventType must not be in headers");
            assertFalse(headers.containsKey("aggregateType"), "aggregateType must not be in headers");
            assertFalse(headers.containsKey("aggregateId"), "aggregateId must not be in headers");

            // relay control is projected into delivery.outbox from the row columns (relay-time only)
            OutboxRelayControl control = envelope.metadata().delivery().outbox().orElseThrow();
            assertEquals(10L, control.messageId());
            assertEquals(rec.carrierId(), control.carrierId(), "carrierId must be projected from the row column");
            assertEquals("order.placed", control.eventType());
            assertEquals("Order", control.aggregateType());
            assertEquals("order-99", control.aggregateId());

            // durable context round-trips from record metadata into the envelope
            assertEquals(
                    rec.metadata().context(),
                    envelope.metadata().context(),
                    "durable context must propagate from record to envelope");
        }

        @Test
        @DisplayName("application headers pass through unchanged — framework control no longer collides")
        void applicationHeadersPassThroughUnchanged() {
            // The application uses keys that the framework previously overwrote in headers; with the
            // split, framework relay control lives in delivery.outbox so the app values now survive.
            Map<String, String> appHeaders = new java.util.HashMap<>();
            appHeaders.put("eventType", "application.value");
            appHeaders.put("x-message-id", "application-msg-id");

            OutboxRecord rec = record(11L, 0, 3, Map.copyOf(appHeaders));

            OutboxEnvelope envelope = relay.buildEnvelope(rec);

            Map<String, String> headers = envelope.headers();
            assertEquals("application.value", headers.get("eventType"), "app header must survive verbatim");
            assertEquals("application-msg-id", headers.get("x-message-id"), "app header must survive verbatim");

            // framework relay control is sourced from the row columns, in delivery.outbox
            OutboxRelayControl control = envelope.metadata().delivery().outbox().orElseThrow();
            assertEquals(11L, control.messageId());
            assertEquals(rec.carrierId(), control.carrierId(), "carrierId must be projected from the row column");
            assertEquals("order.placed", control.eventType());
        }
    }
}
