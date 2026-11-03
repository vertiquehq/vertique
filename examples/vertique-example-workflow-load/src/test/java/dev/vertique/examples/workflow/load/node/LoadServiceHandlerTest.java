// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.vertique.examples.workflow.load.config.LoadConfig;
import dev.vertique.examples.workflow.load.workflow.LoadCallback;
import dev.vertique.examples.workflow.load.workflow.LoadWork;
import dev.vertique.inboxoutbox.TransactionalMessageContext;
import dev.vertique.workflow.ops.RelayedSignals;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.SqlConnection;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Verifies the behaviour the measurement relies on: the delay is a timer and not a blocked thread, the callback is
 * recorded through the relayed form in a transaction, success is reported only after the commit, and every attempt
 * for one instance uses one dedup key.
 */
class LoadServiceHandlerTest {

    private static final UUID INSTANCE = UUID.fromString("00000000-0000-0000-0000-00000000abcd");

    private Vertx vertx;
    private Pool pool;
    private SqlConnection transaction;
    private RelayedSignals<SqlClient> relayed;
    private Promise<Void> commit;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        vertx = mock(Vertx.class);
        pool = mock(Pool.class);
        transaction = mock(SqlConnection.class);
        relayed = mock(RelayedSignals.class);
        commit = Promise.promise();
        when(pool.withTransaction(any(Function.class))).thenAnswer(invocation -> {
            Function<SqlConnection, Future<Object>> work = invocation.getArgument(0);
            return work.apply(transaction).compose(ignored -> commit.future());
        });
        when(relayed.signal(any(), any(), any(), any(), any(), any(), any())).thenReturn(Future.succeededFuture());
    }

    private LoadServiceHandler handler(long delayMs) {
        LoadConfig config = new LoadConfig(delayMs, 1_000L, LoadConfig.Ingress.defaults());
        return new LoadServiceHandler(vertx, pool, () -> relayed, config);
    }

    private static TransactionalMessageContext context(String aggregateId) {
        return new TransactionalMessageContext(7L, "work", "WorkflowInstance", aggregateId, Map.of());
    }

    @Test
    @DisplayName("a zero delay records the callback at once and arms no timer")
    void zeroDelayArmsNoTimer() {
        Future<Void> result = handler(0).work(new LoadWork("k1"), context(INSTANCE.toString()));
        commit.complete();

        assertTrue(result.succeeded());
        verify(vertx, never()).setTimer(anyLong(), any());
        verify(relayed)
                .signal(
                        eq(new WorkflowInstanceId(INSTANCE)),
                        eq("load.callback"),
                        eq(new LoadCallback("k1")),
                        eq("load-callback:k1"),
                        isNull(),
                        isNull(),
                        eq(transaction));
    }

    @Test
    @DisplayName("a positive delay waits on a Vert.x timer and records nothing before it fires")
    @SuppressWarnings("unchecked")
    void positiveDelayWaitsOnATimer() {
        ArgumentCaptor<Handler<Long>> timer = ArgumentCaptor.forClass(Handler.class);
        when(vertx.setTimer(eq(20L), timer.capture())).thenReturn(1L);

        Future<Void> result = handler(20).work(new LoadWork("k2"), context(INSTANCE.toString()));

        assertFalse(result.isComplete());
        verifyNoInteractions(pool);
        timer.getValue().handle(1L);
        verify(pool).withTransaction(any(Function.class));
        assertFalse(result.isComplete(), "success waits for the commit");
        commit.complete();
        assertTrue(result.succeeded());
    }

    @Test
    @DisplayName("success is reported only after the transaction has committed")
    void successWaitsForTheCommit() {
        Future<Void> result = handler(0).work(new LoadWork("k3"), context(INSTANCE.toString()));

        assertFalse(result.isComplete());
        commit.complete();
        assertTrue(result.succeeded());
    }

    @Test
    @DisplayName("a failed transaction fails the delivery so that the relay retries it")
    void failedTransactionFailsTheDelivery() {
        Future<Void> result = handler(0).work(new LoadWork("k4"), context(INSTANCE.toString()));
        IllegalStateException failure = new IllegalStateException("deadlock");
        commit.fail(failure);

        assertTrue(result.failed());
        assertSame(failure, result.cause());
    }

    @Test
    @DisplayName("every delivery attempt of one instance uses the same dedup key")
    void everyAttemptUsesOneDedupKey() {
        LoadServiceHandler handler = handler(0);
        handler.work(new LoadWork("same"), context(INSTANCE.toString()));
        handler.work(new LoadWork("same"), context(INSTANCE.toString()));
        handler.work(new LoadWork("other"), context(INSTANCE.toString()));

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(relayed, org.mockito.Mockito.times(3)).signal(any(), any(), any(), keys.capture(), any(), any(), any());
        assertEquals("load-callback:same", keys.getAllValues().get(0));
        assertEquals("load-callback:same", keys.getAllValues().get(1));
        assertEquals("load-callback:other", keys.getAllValues().get(2));
    }

    @Test
    @DisplayName("a dispatch that names no workflow instance fails without touching the database")
    void missingInstanceFails() {
        for (String aggregateId : new String[] {null, "", "not-a-uuid"}) {
            Future<Void> result = handler(0).work(new LoadWork("k5"), context(aggregateId));

            assertTrue(result.failed(), "aggregate id " + aggregateId);
        }
        verifyNoInteractions(pool);
        verifyNoInteractions(relayed);
    }
}
