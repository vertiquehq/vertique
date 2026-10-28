// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.history;

import dev.vertique.application.VertiqueApplicationHandle;
import dev.vertique.core.VertxModule;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.inboxoutbox.DestinationType;
import dev.vertique.inboxoutbox.OutboxEntry;
import dev.vertique.security.AuthenticationState;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityContexts;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.authz.AuthorizationClaims;
import dev.vertique.security.runtime.ServiceIdentityResolver;
import dev.vertique.workflow.actor.WorkflowActor;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.TaskCompletionCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.history.HistoryGraph.Approve;
import dev.vertique.workflow.postgresql.history.HistoryGraph.Contract;
import dev.vertique.workflow.postgresql.history.HistoryGraph.Relaying;
import dev.vertique.workflow.postgresql.history.HistoryGraph.Start;
import dev.vertique.workflow.services.signal.WorkflowSignalRequest;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * One started graph over the database, in which a user completes a task and an outbox relay under the identity of the
 * service {@code relay-svc} signals the instance on behalf of another user.
 */
final class AuditRun implements AutoCloseable {

    /** The user who completes the task. */
    static final String TASK_USER = "u-1";

    /** The user whose outbox row the relay delivers. */
    static final String SIGNALLING_USER = "u-2";

    /** The service the relay acts as. */
    static final String RELAY_SERVICE = "relay-svc";

    private static final Duration DELIVERY = Duration.ofSeconds(30);

    private final Vertx vertx;
    private final Pool pool;
    private final Relaying graph;
    private final VertiqueApplicationHandle<?> handle;

    private AuditRun(Vertx vertx, Pool pool, Relaying graph, VertiqueApplicationHandle<?> handle) {
        this.vertx = vertx;
        this.pool = pool;
        this.graph = graph;
        this.handle = handle;
    }

    /** Starts a graph whose relay identity is the service {@code relay-svc}. */
    static AuditRun start(Vertx vertx, PostgresContainer db) {
        PgConnectOptions options = HistoryGraph.options(db);
        reset(vertx, options);
        Pool pool = HistoryGraph.pool(vertx, options);
        ServiceIdentityResolver relayService =
                origin -> SecurityIdentity.service(new PrincipalRef(PrincipalType.SERVICE, RELAY_SERVICE, Map.of()));
        Relaying graph = DaggerHistoryGraph_Relaying.builder()
                .vertxModule(new VertxModule(vertx, HistoryGraph.config()))
                .infrastructure(new HistoryGraph.Infrastructure(pool, options))
                .resolverModule(new HistoryGraph.ResolverModule(relayService))
                .build();
        VertiqueApplicationHandle<?> handle;
        try {
            handle = await(HistoryGraph.start(vertx, graph), Duration.ofSeconds(45));
        } catch (RuntimeException | Error failure) {
            await(pool.close());
            throw failure;
        }
        return new AuditRun(vertx, pool, graph, handle);
    }

    /** Empties what an earlier proof of the class left in the workflow tables, the outbox and the inbox. */
    private static void reset(Vertx vertx, PgConnectOptions options) {
        Pool admin = HistoryGraph.pool(vertx, options);
        try {
            await(admin.query("TRUNCATE workflow_commands, workflow_command_queues, workflow_history, workflow_dedup,"
                            + " workflow_timers, workflow_tasks, workflow_instances, workflow_definitions CASCADE")
                    .execute());
            await(admin.query("DELETE FROM outbox").execute());
            await(admin.query("DELETE FROM inbox").execute());
        } finally {
            await(admin.close());
        }
    }

    Relaying graph() {
        return graph;
    }

    Pool pool() {
        return pool;
    }

    static SecurityIdentity user(String id) {
        return SecurityIdentity.user(new PrincipalRef(PrincipalType.USER, id, Map.of()));
    }

    /** Starts an instance and waits until it waits at its task. */
    WorkflowInstanceId startAtTask(String ref) {
        StartCommand command = new StartCommand(
                HistoryGraph.DEFINITION_ID,
                Contract.class.getName(),
                new Start(ref),
                ref,
                null,
                null,
                null,
                null,
                null);
        WorkflowInstanceId id =
                await(pool.withTransaction(tx -> graph.transactionalOperations().start(command, tx)));
        await(graph.executionProbe().awaitIdle(id, Duration.ofSeconds(15)));
        return id;
    }

    /** Completes the instance's task as the user, in a transaction of the user's, and waits until the instance is idle. */
    void completeTaskAs(String userId, WorkflowInstanceId id) {
        UUID taskId = await(pool.preparedQuery("SELECT task_id FROM workflow_tasks WHERE workflow_id = $1")
                        .execute(Tuple.of(id.value())))
                .iterator()
                .next()
                .getUUID("task_id");
        TaskCompletionCommand command = new TaskCompletionCommand(
                taskId, "approve", new Approve("fine"), "task-key", new WorkflowActor.User(userId), null);
        await(onDuplicatedContext(() -> {
            ContextHolder.Scope bound = graph.contextHolder().bind(SecurityContext.class, live(user(userId)));
            return pool.withTransaction(tx -> graph.taskCallbacks().taskCompleted(command, tx))
                    .onComplete(ignored -> bound.close());
        }));
        await(graph.executionProbe().awaitIdle(id, Duration.ofSeconds(15)));
    }

    /**
     * Writes an outbox row in a transaction of the user's that signals the instance through the signal endpoint, waits
     * until the relay has recorded the signal and until the instance is idle again.
     */
    void relaySignalAs(String userId, WorkflowInstanceId id, String key) {
        OutboxEntry entry = OutboxEntry.builder()
                .destinationType(DestinationType.SERVICE)
                .destination("workflow.signals.post")
                .eventType(HistoryGraph.EVENT_TYPE)
                .aggregateType("WorkflowInstance")
                .aggregateId(id.value().toString())
                .payload(WorkflowSignalRequest.instance(id, HistoryGraph.SIGNAL, Map.of("value", key), key))
                .build();
        SecurityContext live = live(user(userId));
        await(onDuplicatedContext(() -> {
            ContextHolder.Scope captured = graph.identitySnapshotCapture().captureFrom(live);
            return pool.withTransaction(tx -> graph.outboxService().publish(tx, entry))
                    .onComplete(ignored -> captured.close());
        }));
        awaitCondition("the relay to record the signal", () -> signalCommands(id) > 0);
        awaitCondition("the signal to settle", () -> openCommands(id) == 0);
        await(graph.executionProbe().awaitIdle(id, Duration.ofSeconds(15)));
    }

    /** The id of the one command of the kind that the instance has. */
    long commandIdOf(WorkflowInstanceId id, String kind) {
        Row row = await(pool.preparedQuery("SELECT id FROM workflow_commands WHERE workflow_id = $1 AND kind = $2")
                        .execute(Tuple.of(id.value(), kind)))
                .iterator()
                .next();
        return row.getLong("id");
    }

    private long signalCommands(WorkflowInstanceId id) {
        return scalar("SELECT count(*) FROM workflow_commands WHERE workflow_id = $1 AND kind = 'SIGNAL'", id);
    }

    private long openCommands(WorkflowInstanceId id) {
        return scalar(
                "SELECT count(*) FROM workflow_commands WHERE workflow_id = $1 AND state IN"
                        + " ('PENDING', 'DEFERRED', 'CLAIMED', 'PARKED')",
                id);
    }

    private long scalar(String sql, WorkflowInstanceId id) {
        Row row = await(pool.preparedQuery(sql).execute(Tuple.of(id.value())))
                .iterator()
                .next();
        return row.getLong(0);
    }

    private static SecurityContext live(SecurityIdentity caller) {
        return SecurityContexts.assemble(
                caller,
                new AuthenticationState(
                        DefaultAuthMethod.jwt(), List.of(), Optional.empty(), Optional.empty(), Map.of()),
                AuthorizationClaims.empty(),
                Optional.empty());
    }

    private void awaitCondition(String description, BooleanSupplier condition) {
        long deadline = System.nanoTime() + DELIVERY.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for " + description, e);
            }
        }
        throw new AssertionError("timed out waiting for " + description);
    }

    private <T> Future<T> onDuplicatedContext(Supplier<Future<T>> work) {
        Promise<T> result = Promise.promise();
        ContextInternal context = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        context.runOnContext(ignored -> {
            try {
                work.get().onComplete(result);
            } catch (Throwable e) {
                result.tryFail(e);
            }
        });
        return result.future();
    }

    @Override
    public void close() {
        try {
            await(handle.shutdown(), Duration.ofSeconds(30));
        } finally {
            await(pool.close());
        }
    }

    static <T> T await(Future<T> future) {
        return await(future, Duration.ofSeconds(20));
    }

    static <T> T await(Future<T> future, Duration timeout) {
        try {
            return future.toCompletionStage().toCompletableFuture().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new AssertionError("the future failed", e.getCause());
        } catch (InterruptedException | java.util.concurrent.TimeoutException e) {
            throw new AssertionError("the future did not complete in " + timeout, e);
        }
    }
}
