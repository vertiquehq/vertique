// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.node;

import dev.vertique.examples.workflow.load.config.LoadConfig;
import dev.vertique.examples.workflow.load.workflow.LoadCallback;
import dev.vertique.examples.workflow.load.workflow.LoadWork;
import dev.vertique.inboxoutbox.TransactionalMessageContext;
import dev.vertique.workflow.ops.RelayedSignals;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlClient;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import java.util.UUID;

/**
 * The dispatch handler of the load workload. Every node registers one.
 *
 * <p>The workflow's dispatch reaches it through the outbox relay. The handler stands in for a fast internal
 * service: it waits on a Vert.x timer for the configured delay without blocking a thread, then records the callback
 * signal of the instance in a database transaction and returns success only after that transaction has committed.
 *
 * <p>Properties the measurement relies on:
 * <ul>
 *   <li><b>No per-call state.</b> The handler holds no map, counter or queue; each call is a chain of futures that
 *       ends when the call ends. A zero delay skips the timer.</li>
 *   <li><b>The callback is never refused by the intake limit.</b> It is recorded through {@link RelayedSignals},
 *       which the limit does not apply to, because the signal is already durable in the outbox and a refusal would
 *       only spend delivery attempts.</li>
 *   <li><b>A redelivery records nothing new.</b> Every attempt for one instance uses the same dedup key
 *       ({@link LoadCallback#dedupKeyOf(String)}), so the engine records the signal once.</li>
 *   <li><b>No outbox row and no log line per call.</b> The callback is a recorded command, not a dispatch.</li>
 * </ul>
 *
 * <p>The delay occupies one slot of the relay while it runs, as a real service call would. Whether relay capacity
 * limits the workload is a question for measurement, not something this class decides.
 */
@Singleton
public final class LoadServiceHandler {

    private final Vertx vertx;
    private final Pool pool;
    private final Provider<RelayedSignals<SqlClient>> relayedSignals;
    private final long delayMs;

    /**
     * Creates the handler.
     *
     * <p>The recording of already-durable signals is injected as a {@link Provider}: the service contract registry
     * that holds this handler is itself needed to build the engine that provides the recording, so a direct
     * dependency would be a cycle.
     *
     * @param vertx the Vert.x instance that runs the delay timer
     * @param pool the database pool the callback transaction uses
     * @param relayedSignals lazy provider of the recording of already-durable signals
     * @param config the harness settings; the delay is {@link LoadConfig#handlerDelayMs()}
     */
    @Inject
    public LoadServiceHandler(
            Vertx vertx, Pool pool, Provider<RelayedSignals<SqlClient>> relayedSignals, LoadConfig config) {
        this.vertx = vertx;
        this.pool = pool;
        this.relayedSignals = relayedSignals;
        this.delayMs = config.handlerDelayMs();
    }

    /**
     * Handles one dispatch: waits for the delay, then records the callback of the dispatching instance.
     *
     * @param command the dispatched command
     * @param context the delivery context the relay rebuilt; its aggregate id is the id of the instance
     * @return a future that succeeds after the callback transaction has committed, or fails when the instance id is
     *     missing or the transaction fails, which the relay answers with a retry
     */
    public Future<Void> work(LoadWork command, TransactionalMessageContext context) {
        WorkflowInstanceId instance;
        try {
            instance = new WorkflowInstanceId(UUID.fromString(context.aggregateId()));
        } catch (RuntimeException e) {
            return Future.failedFuture(new IllegalArgumentException("The dispatch does not name a workflow instance"));
        }
        return afterDelay()
                .compose(ignored -> pool.withTransaction(tx -> relayedSignals
                        .get()
                        .signal(
                                instance,
                                LoadCallback.SIGNAL_NAME,
                                new LoadCallback(command.key()),
                                LoadCallback.dedupKeyOf(command.key()),
                                null,
                                null,
                                tx)));
    }

    private Future<Void> afterDelay() {
        if (delayMs <= 0) {
            return Future.succeededFuture();
        }
        Promise<Void> elapsed = Promise.promise();
        vertx.setTimer(delayMs, id -> elapsed.complete());
        return elapsed.future();
    }
}
