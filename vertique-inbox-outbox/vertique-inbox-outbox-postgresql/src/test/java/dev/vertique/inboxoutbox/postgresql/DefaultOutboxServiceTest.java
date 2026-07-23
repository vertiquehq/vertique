// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
import dev.vertique.db.exception.DataAccessException;
import dev.vertique.inboxoutbox.OutboxDeliveryMetadata;
import dev.vertique.inboxoutbox.OutboxEntry;
import dev.vertique.inboxoutbox.OutboxMetadata;
import dev.vertique.inboxoutbox.OutboxRepository;
import dev.vertique.inboxoutbox.exception.InboxOutboxPersistenceException;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.SqlClient;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
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
 * Tests for {@link DefaultOutboxService} — captures durable context into {@link OutboxMetadata},
 * delegates publish to the underlying {@link OutboxRepository}, and wraps repository failures with
 * {@link InboxOutboxExceptionMapper}.
 */
@ExtendWith({VertxExtension.class, MockitoExtension.class})
@MockitoSettings(strictness = Strictness.LENIENT)
@Timeout(value = 15, unit = TimeUnit.SECONDS)
class DefaultOutboxServiceTest {

    @Mock
    OutboxRepository repository;

    @Mock
    SqlClient tx;

    @Mock
    OutboxEntry entry;

    DefaultOutboxService service;

    @BeforeEach
    void setUp() {
        DefaultContextHolder holder = new DefaultContextHolder();
        DurableContextPropagator propagator = new DurableContextPropagator(
                new DurableContextMetadataRegistry(Set.of(), Set.of()), holder, new ContextScopeBinder(holder));
        service = new DefaultOutboxService(repository, propagator, new InboxOutboxExceptionMapper());
    }

    @Test
    @DisplayName("publish delegates to repository insert with captured metadata")
    void publishDelegatesToRepositoryInsertWithMetadata() {
        when(repository.insert(eq(entry), any(OutboxMetadata.class), any(UUID.class), eq(tx)))
                .thenReturn(Future.succeededFuture(42L));

        Future<Long> result = service.publish(tx, entry);

        assertTrue(result.succeeded(), "publish future should succeed");
        assertEquals(42L, result.result(), "should return the repository-assigned id");
        verify(repository).insert(eq(entry), any(OutboxMetadata.class), any(UUID.class), eq(tx));
    }

    @Test
    @DisplayName("publish passes OutboxMetadata with empty delivery and captured context")
    void publishPassesOutboxMetadataWithCapturedContext() {
        ArgumentCaptor<OutboxMetadata> metadataCaptor = forClass(OutboxMetadata.class);
        when(repository.insert(any(), metadataCaptor.capture(), any(), any())).thenReturn(Future.succeededFuture(1L));

        service.publish(tx, entry);

        OutboxMetadata captured = metadataCaptor.getValue();
        assertNotNull(captured, "metadata must not be null");
        // No encoders registered, so context is empty
        assertTrue(captured.context().isEmpty(), "context must be empty when no encoders are registered");
        // Delivery is always empty at publish time in this task
        assertEquals(
                OutboxDeliveryMetadata.empty(), captured.delivery(), "delivery metadata must be empty at publish time");
    }

    @Test
    @DisplayName("publish does not merge durable context into entry headers")
    void publishDoesNotMergeDurableContextIntoHeaders() {
        OutboxEntry entryWithHeaders = mock(OutboxEntry.class);
        when(entryWithHeaders.headers()).thenReturn(java.util.Map.of("x-app", "val"));
        when(repository.insert(any(), any(), any(), any())).thenReturn(Future.succeededFuture(1L));

        service.publish(tx, entryWithHeaders);

        // The entry passed to insert must be the same object — headers are NOT rebuilt
        verify(repository).insert(eq(entryWithHeaders), any(OutboxMetadata.class), any(UUID.class), eq(tx));
        // Confirm original headers are untouched (no durable keys injected)
        assertFalse(
                entryWithHeaders.headers().containsKey("vertique-context"),
                "durable context must not be injected into entry headers");
    }

    @Test
    @DisplayName("publish wraps repository DataAccessException as InboxOutboxPersistenceException")
    void publishWrapsRepositoryDataAccessExceptionAsPersistenceException() {
        DataAccessException dbFailure = new DataAccessException("connection lost", null, null, null, null);
        when(repository.insert(any(), any(), any(), any())).thenReturn(Future.failedFuture(dbFailure));

        Future<Long> result = service.publish(tx, entry);

        assertTrue(result.failed(), "publish future should fail when repository fails");
        assertInstanceOf(
                InboxOutboxPersistenceException.class,
                result.cause(),
                "DataAccessException from repository must be wrapped in InboxOutboxPersistenceException");
        assertSame(dbFailure, result.cause().getCause(), "original DataAccessException must be chained as the cause");
    }

    @Nested
    @DisplayName("F3b row-carrier binding (PRD identity-002 §14.6/A9/F3b)")
    class CarrierBinding {

        /** Namespace used by the recording test encoder/decoder pair. */
        private static final String NS = "test-correlation";

        /**
         * Minimal typed durable value used to trigger the recording encoder.
         *
         * @param value an arbitrary payload value
         */
        record TestValue(String value) implements ContextValue {}

        /** Runs the given task on a fresh duplicated Vert.x context (holder writes require one). */
        private static void runOnDuplicated(Vertx vertx, io.vertx.core.Handler<Void> task) {
            ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
            dup.runOnContext(task);
        }

        @Test
        @DisplayName("publish threads a real per-row carrier into the encode context: carrierId == the id"
                + " persisted via repository.insert, target kind == outbox-relay, address == carrierId")
        void publishThreadsRealCarrierIntoEncodeContext(Vertx vertx, VertxTestContext ctx) {
            DefaultContextHolder holder = new DefaultContextHolder();
            java.util.concurrent.atomic.AtomicReference<DurableEncodeContext> observed =
                    new java.util.concurrent.atomic.AtomicReference<>();
            DurableContextMetadataEncoder<TestValue> recordingEncoder = new DurableContextMetadataEncoder<>() {
                @Override
                public Class<TestValue> type() {
                    return TestValue.class;
                }

                @Override
                public String namespace() {
                    return NS;
                }

                @Override
                public DurableMetadata encode(TestValue value, DurableEncodeContext context) {
                    observed.set(context);
                    return DurableMetadata.of(NS, new JsonObject().put("v", value.value()));
                }
            };
            DurableContextPropagator propagator = new DurableContextPropagator(
                    new DurableContextMetadataRegistry(Set.of(recordingEncoder), Set.of()),
                    holder,
                    new ContextScopeBinder(holder));
            DefaultOutboxService carrierService =
                    new DefaultOutboxService(repository, propagator, new InboxOutboxExceptionMapper());

            ArgumentCaptor<UUID> carrierIdCaptor = forClass(UUID.class);
            when(repository.insert(eq(entry), any(OutboxMetadata.class), carrierIdCaptor.capture(), eq(tx)))
                    .thenReturn(Future.succeededFuture(1L));

            runOnDuplicated(vertx, v -> {
                try (ContextHolder.Scope scope = holder.bind(TestValue.class, new TestValue("v-1"))) {
                    carrierService.publish(tx, entry);
                    ctx.verify(() -> {
                        DurableEncodeContext encodeContext = observed.get();
                        assertNotNull(encodeContext, "encoder must have been invoked");
                        assertTrue(
                                encodeContext.carrier().isPresent(),
                                "the outbox boundary must thread a real carrier into the encode context");
                        UUID persistedCarrierId = carrierIdCaptor.getValue();
                        assertEquals(
                                persistedCarrierId.toString(),
                                encodeContext.carrier().orElseThrow().carrierId(),
                                "the signed carrierId must equal the carrierId persisted via repository.insert");
                        assertEquals(
                                "outbox-relay",
                                encodeContext.carrier().orElseThrow().target().kind(),
                                "target kind must be the canonical outbox-relay string (F7a)");
                        assertEquals(
                                persistedCarrierId.toString(),
                                encodeContext.carrier().orElseThrow().target().address(),
                                "target address must equal the carrierId (both sides derive it identically)");
                    });
                    ctx.completeNow();
                } catch (Throwable t) {
                    ctx.failNow(t);
                }
            });
        }

        @Test
        @DisplayName("two publish calls generate distinct carrier ids")
        void publishGeneratesDistinctCarrierIdsPerCall() {
            ArgumentCaptor<UUID> carrierIdCaptor = forClass(UUID.class);
            when(repository.insert(any(), any(), carrierIdCaptor.capture(), any()))
                    .thenReturn(Future.succeededFuture(1L));

            service.publish(tx, entry);
            service.publish(tx, entry);

            assertEquals(2, carrierIdCaptor.getAllValues().size());
            assertNotEquals(
                    carrierIdCaptor.getAllValues().get(0),
                    carrierIdCaptor.getAllValues().get(1),
                    "each publish call must allocate a fresh carrier id");
        }
    }
}
