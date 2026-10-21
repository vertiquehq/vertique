// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.registry;

import dev.vertique.application.VertiqueApplicationBootstrap;
import dev.vertique.application.VertiqueApplicationHandle;
import dev.vertique.core.VertiqueRuntime;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.definition.service.WorkflowDefinitionService;
import dev.vertique.workflow.postgresql.registry.ManagedDefinitionDatabase.AppComponent;
import dev.vertique.workflow.postgresql.registry.ManagedDefinitionDatabase.BootObservation;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.DefinitionStatus;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRegistryStore;
import dev.vertique.workflow.registry.store.WorkflowDefinitionsConfig;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Transaction;
import io.vertx.sqlclient.Tuple;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Shared fixtures for tests that run several application graphs against one database and hold the definition id
 * lock to line up competing operations: the graph, the lock barrier and the per-scenario cleanup.
 */
final class DefinitionRaceFixtures {

    private DefinitionRaceFixtures() {}

    static <T> T await(Future<T> future) {
        try {
            return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while awaiting a future", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException(cause);
        } catch (TimeoutException e) {
            throw new AssertionError("A future did not complete within ten seconds", e);
        }
    }

    static Throwable failureOf(Future<?> future) {
        Future<Throwable> outcome =
                future.transform(result -> Future.succeededFuture(result.failed() ? result.cause() : null));
        return await(outcome);
    }

    static long lockKey(String definitionId) {
        try {
            return ByteBuffer.wrap(MessageDigest.getInstance("SHA-256")
                            .digest(("vertique.workflow.definition:" + definitionId).getBytes(StandardCharsets.UTF_8)))
                    .getLong();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    record Stored(
            String scopeKey, long version, String status, String token, String planHash, OffsetDateTime changedAt) {}

    record Node(Pool pool, WorkflowDefinitionSqlObserver observer, AppComponent app) {

        WorkflowDefinitionService service() {
            return app.definitionService();
        }

        WorkflowRegistry registry() {
            return app.workflowRegistry();
        }

        WorkflowDefinitionRegistryStore store() {
            return app.definitionRegistryStore();
        }
    }

    @FunctionalInterface
    interface Cleanup {
        void run() throws Exception;
    }

    /** Holds a definition id's advisory lock in a test-owned transaction so competing operations queue behind it. */
    static final class IdLockBarrier {
        private final Pool pool;
        private final long key;
        private final SqlConnection connection;
        private final Transaction transaction;
        private boolean released;

        private IdLockBarrier(Pool pool, long key, SqlConnection connection, Transaction transaction) {
            this.pool = pool;
            this.key = key;
            this.connection = connection;
            this.transaction = transaction;
        }

        static IdLockBarrier hold(Pool pool, String definitionId) {
            SqlConnection connection = await(pool.getConnection());
            try {
                Transaction transaction = await(connection.begin());
                long key = lockKey(definitionId);
                await(connection
                        .preparedQuery("SELECT pg_advisory_xact_lock($1)")
                        .execute(Tuple.of(key)));
                return new IdLockBarrier(pool, key, connection, transaction);
            } catch (RuntimeException | Error failure) {
                await(connection.close());
                throw failure;
            }
        }

        /** Proves the precondition that the expected number of operations is blocked on this lock. */
        void awaitQueued(int expected) {
            long high = (key >>> 32) & 0xFFFFFFFFL;
            long low = key & 0xFFFFFFFFL;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            long waiting = -1;
            while (System.nanoTime() < deadline) {
                waiting = await(pool.preparedQuery("""
                                SELECT count(*) AS waiting FROM pg_locks
                                WHERE locktype = 'advisory' AND NOT granted AND objsubid = 1
                                  AND classid::bigint = $1 AND objid::bigint = $2
                                """).execute(Tuple.of(high, low)))
                        .iterator()
                        .next()
                        .getLong("waiting");
                if (waiting == expected) return;
                Thread.onSpinWait();
            }
            throw new AssertionError("Expected " + expected + " operations queued on the id lock but saw " + waiting);
        }

        void release() {
            if (released) return;
            released = true;
            try {
                await(transaction.rollback());
            } finally {
                await(connection.close());
            }
        }
    }

    /** Owns the application graphs, pools and barriers of one scenario and closes them in reverse order. */
    static final class Fixture implements AutoCloseable {
        private final Vertx vertx;
        private final PostgresContainer db;
        private final Deque<Cleanup> cleanups = new ArrayDeque<>();
        private Pool admin;

        Fixture(Vertx vertx, PostgresContainer db) {
            this.vertx = vertx;
            this.db = db;
        }

        void onClose(Cleanup cleanup) {
            cleanups.push(cleanup);
        }

        /** Starts an independent application graph with its own observed pool on the shared database. */
        Node node() {
            WorkflowDefinitionSqlObserver observer = new WorkflowDefinitionSqlObserver();
            Pool pool = observer.observe(ManagedDefinitionDatabase.openPool(vertx, db));
            onClose(() -> await(pool.close()));
            AppComponent app =
                    ManagedDefinitionDatabase.app(vertx, pool, new BootObservation("race-unused", 1, "unused", false));
            VertiqueApplicationHandle<?> handle = await(VertiqueApplicationBootstrap.start(
                    VertiqueRuntime.of(vertx, new JsonObject()), ignoredRuntime -> app));
            onClose(() -> await(handle.shutdown()));
            return new Node(pool, observer, app);
        }

        /** Returns the unobserved pool used for test-owned SQL, so application gates never see it. */
        Pool admin() {
            if (admin == null) {
                admin = ManagedDefinitionDatabase.openPool(vertx, db);
                Pool opened = admin;
                onClose(() -> await(opened.close()));
            }
            return admin;
        }

        IdLockBarrier holdIdLock(String definitionId) {
            IdLockBarrier barrier = IdLockBarrier.hold(admin(), definitionId);
            onClose(barrier::release);
            return barrier;
        }

        /** Clears the ids now and again on close, so rows with placeholder sources never reach a later start. */
        void ownRows(String... definitionIds) {
            deleteRows(definitionIds);
            onClose(() -> deleteRows(definitionIds));
        }

        void deleteRows(String... definitionIds) {
            for (String id : definitionIds) {
                await(admin().preparedQuery("DELETE FROM workflow_definitions WHERE definition_id = $1")
                        .execute(Tuple.of(id)));
            }
        }

        List<Stored> stored(String definitionId) {
            List<Stored> rows = new ArrayList<>();
            for (Row row : await(admin().preparedQuery("""
                            SELECT scope_key, definition_version, status, publish_token::text AS publish_token,
                                   plan_hash, status_changed_at
                            FROM workflow_definitions WHERE definition_id = $1
                            ORDER BY scope_key NULLS FIRST, definition_version
                            """).execute(Tuple.of(definitionId)))) {
                rows.add(new Stored(
                        row.getString("scope_key"),
                        row.getLong("definition_version"),
                        row.getString("status"),
                        row.getString("publish_token"),
                        row.getString("plan_hash"),
                        row.getOffsetDateTime("status_changed_at")));
            }
            rows.sort(Comparator.comparing(Stored::scopeKey).thenComparingLong(Stored::version));
            return rows;
        }

        /** Ages one outstanding claim past twice the staleness bound without touching any other row. */
        void ageClaim(WorkflowDefinitionsConfig config, DefinitionKey key, String token) {
            long ageSeconds = 2 * config.maxStalenessSeconds() + 60;
            await(admin().preparedQuery("""
                            UPDATE workflow_definitions
                            SET status_changed_at = clock_timestamp() - ($5::bigint * interval '1 second')
                            WHERE scope_key IS NOT DISTINCT FROM $1 AND definition_id = $2
                              AND definition_version = $3 AND status = 'PUBLISHING' AND publish_token = $4::uuid
                            """)
                    .execute(Tuple.of(key.scopeKey(), key.definitionId(), key.definitionVersion(), token, ageSeconds)));
        }

        /** Moves a row to a status that a candidate cannot reach through the public operations. */
        void forceStatus(DefinitionKey key, DefinitionStatus status) {
            await(admin().preparedQuery("""
                            UPDATE workflow_definitions SET status = $4
                            WHERE scope_key IS NOT DISTINCT FROM $1 AND definition_id = $2 AND definition_version = $3
                            """)
                    .execute(Tuple.of(key.scopeKey(), key.definitionId(), key.definitionVersion(), status.name())));
        }

        @Override
        public void close() {
            AssertionError failure = null;
            while (!cleanups.isEmpty()) {
                try {
                    cleanups.pop().run();
                } catch (Throwable cleanupFailure) {
                    if (failure == null) failure = new AssertionError("Scenario cleanup failed", cleanupFailure);
                    else failure.addSuppressed(cleanupFailure);
                }
            }
            if (failure != null) throw failure;
        }
    }
}
