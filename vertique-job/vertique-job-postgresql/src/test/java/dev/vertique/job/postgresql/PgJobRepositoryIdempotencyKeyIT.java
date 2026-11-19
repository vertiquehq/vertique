// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.DbSchemas;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.job.JobExecution;
import dev.vertique.job.JobState;
import dev.vertique.job.JobType;
import dev.vertique.job.ProgressSnapshot;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Integration tests for the idempotency key of {@link PgJobRepository#save}: the unique index, the enqueue that
 * returns the existing execution, and the states that hold or release a key.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class PgJobRepositoryIdempotencyKeyIT {

    private static final String HANDLER = "job.delayed.keyed";

    static final PostgresContainer db =
            new PostgresContainer().withDatabaseName("job_key_test").withMigration("classpath:db/migration/job");

    static Pool pool;
    static PgJobRepository repository;

    @BeforeAll
    static void setUp(Vertx vertx, VertxTestContext ctx) {
        DbPoolConfig config = db.toPoolConfig();
        pool = PgBuilder.pool()
                .with(new PoolOptions().setMaxSize(10))
                .connectingTo(new PgConnectOptions()
                        .setHost(config.host())
                        .setPort(config.port())
                        .setDatabase(config.database())
                        .setUser(config.user())
                        .setPassword(config.password()))
                .using(vertx)
                .build();
        repository = new PgJobRepository(pool, new PgDbExceptionMapper(), DbSchemas.empty(), "key-node");
        ctx.completeNow();
    }

    @AfterAll
    static void tearDown() {
        if (pool != null) {
            pool.close();
        }
    }

    // --- Helpers ---

    private static JobExecution execution(String handler, String scopeKey, String key) {
        return new JobExecution(
                UUID.randomUUID(),
                "label-" + UUID.randomUUID(),
                JobType.DELAYED,
                handler,
                "key-queue-" + UUID.randomUUID(),
                JobState.ENQUEUED,
                0,
                5,
                null,
                0,
                null,
                Instant.now().minusSeconds(5),
                null,
                null,
                null,
                null,
                null,
                ProgressSnapshot.EMPTY,
                null,
                null,
                null,
                scopeKey,
                key);
    }

    private static String freshKey() {
        return "key-" + UUID.randomUUID();
    }

    private static Future<Long> countHolding(String handler, String scopeKey, String key) {
        return pool.preparedQuery("SELECT count(*) AS n FROM job_executions"
                        + " WHERE handler = $1 AND scope_key = $2 AND idempotency_key = $3")
                .execute(Tuple.of(handler, scopeKey, key))
                .map(rows -> rows.iterator().next().getLong("n"));
    }

    private static Future<Void> forceState(UUID id, JobState state) {
        return pool.preparedQuery("UPDATE job_executions SET state = $2 WHERE id = $1")
                .execute(Tuple.of(id, state.name()))
                .mapEmpty();
    }

    // --- Tests ---

    @Test
    @DisplayName("a second save with the held key returns the first execution and stores nothing")
    void secondSaveReturnsExistingExecution(VertxTestContext ctx) {
        // Given: an execution that holds a key
        String key = freshKey();
        JobExecution first = execution(HANDLER, "default", key);
        JobExecution second = execution(HANDLER, "default", key);

        // When: a second execution with the same key is saved
        repository
                .save(first)
                .compose(firstId -> repository.save(second).map(secondId -> List.of(firstId, secondId)))
                .compose(ids -> countHolding(HANDLER, "default", key).map(n -> List.of(ids, n)))
                .onSuccess(result -> ctx.verify(() -> {
                    // Then: both saves return the first execution's id and one row holds the key
                    @SuppressWarnings("unchecked")
                    List<UUID> ids = (List<UUID>) result.get(0);
                    assertEquals(first.id(), ids.get(0));
                    assertEquals(first.id(), ids.get(1));
                    assertEquals(1L, result.get(1));
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("the key is read back with the execution")
    void keyRoundTrips(VertxTestContext ctx) {
        String key = freshKey();
        JobExecution exec = execution(HANDLER, "default", key);
        repository
                .save(exec)
                .compose(repository::findById)
                .onSuccess(found -> ctx.verify(() -> {
                    assertEquals(key, found.orElseThrow().idempotencyKey());
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("executions without a key are never de-duplicated")
    void noKeyNoDeduplication(VertxTestContext ctx) {
        JobExecution a = execution(HANDLER, "default", null);
        JobExecution b = execution(HANDLER, "default", null);
        repository
                .save(a)
                .compose(x -> repository.save(b))
                .onSuccess(secondId -> ctx.verify(() -> {
                    assertEquals(b.id(), secondId);
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("the same key under another handler or another scope is a different key")
    void keyIsScopedByHandlerAndScope(VertxTestContext ctx) {
        // Given: one execution holding a key
        String key = freshKey();
        JobExecution base = execution(HANDLER, "acme", key);
        JobExecution otherHandler = execution("job.delayed.other", "acme", key);
        JobExecution otherScope = execution(HANDLER, "globex", key);

        // When: the same key is saved for another handler and for another scope
        repository
                .save(base)
                .compose(x -> repository.save(otherHandler))
                .compose(x -> repository.save(otherScope))
                .onSuccess(lastId -> ctx.verify(() -> {
                    // Then: each stored its own execution
                    assertEquals(otherScope.id(), lastId);
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @ParameterizedTest(name = "an execution in {0} holds its key")
    @EnumSource(
            value = JobState.class,
            names = {"ENQUEUED", "PROCESSING", "FAILED", "ABANDONED"})
    void unfinishedStatesHoldTheKey(JobState state, VertxTestContext ctx) {
        // Given: an execution in an unfinished state
        String key = freshKey();
        JobExecution holder = execution(HANDLER, "default", key);
        repository
                .save(holder)
                .compose(id -> forceState(id, state))
                // When: the key is enqueued again
                .compose(x -> repository.save(execution(HANDLER, "default", key)))
                .onSuccess(id -> ctx.verify(() -> {
                    // Then: the holder is returned
                    assertEquals(holder.id(), id);
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @ParameterizedTest(name = "an execution in {0} releases its key")
    @EnumSource(
            value = JobState.class,
            names = {"SUCCEEDED", "CANCELLED", "DEAD_LETTER"})
    void finishedStatesReleaseTheKey(JobState state, VertxTestContext ctx) {
        // Given: an execution that has finished
        String key = freshKey();
        JobExecution finished = execution(HANDLER, "default", key);
        JobExecution next = execution(HANDLER, "default", key);
        repository
                .save(finished)
                .compose(id -> forceState(id, state))
                // When: the key is enqueued again
                .compose(x -> repository.save(next))
                .compose(id -> countHolding(HANDLER, "default", key).map(n -> List.of(id, n)))
                .onSuccess(result -> ctx.verify(() -> {
                    // Then: a new execution is stored and the finished one stays
                    assertEquals(next.id(), result.get(0));
                    assertEquals(2L, result.get(1));
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("a retry cycle of a keyed execution never violates the index")
    void retryCycleKeepsTheKeyHeld(VertxTestContext ctx) {
        // Given: a keyed execution that failed and was re-enqueued, with a second enqueue of the key in between
        String key = freshKey();
        JobExecution holder = execution(HANDLER, "default", key);
        List<UUID> returned = new ArrayList<>();
        repository
                .save(holder)
                .compose(id -> forceState(id, JobState.PROCESSING))
                // When: the execution fails (FAILED, then ENQUEUED for the retry) and the key is enqueued during it
                .compose(x ->
                        repository.completeExecution(holder.id(), JobState.FAILED, "boom", "E", ProgressSnapshot.EMPTY))
                .compose(x -> repository.save(execution(HANDLER, "default", key)))
                .compose(id -> {
                    returned.add(id);
                    return repository.scheduleRetry(holder.id(), Instant.now().minusSeconds(1), 1);
                })
                .compose(x -> repository.save(execution(HANDLER, "default", key)))
                .onSuccess(id -> ctx.verify(() -> {
                    // Then: the retry update succeeded and every enqueue returned the same holder
                    returned.add(id);
                    assertEquals(List.of(holder.id(), holder.id()), returned);
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("concurrent first saves of one key store one execution and all return it")
    void concurrentSavesStoreOne(VertxTestContext ctx) {
        // Given: many executions with the same key, none stored yet
        String key = freshKey();
        List<Future<UUID>> saves = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            saves.add(repository.save(execution(HANDLER, "default", key)));
        }

        // When: they are saved at once
        Future.all(saves)
                .compose(all -> countHolding(HANDLER, "default", key).map(n -> List.of(all.<UUID>list(), n)))
                .onSuccess(result -> ctx.verify(() -> {
                    // Then: one row holds the key and every call returned its id
                    @SuppressWarnings("unchecked")
                    List<UUID> ids = (List<UUID>) result.get(0);
                    assertEquals(1, ids.stream().distinct().count());
                    assertEquals(1L, result.get(1));
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("a save races the holder finishing and always returns an execution that exists")
    void saveRacingTheHolderFinishing(VertxTestContext ctx) {
        // Given: many keyed holders, each finished while a save of its key is in flight
        List<Future<UUID>> saves = new ArrayList<>();
        List<Future<Void>> finishes = new ArrayList<>();
        Future<Void> setUp = Future.succeededFuture();
        List<String> keys = new ArrayList<>();
        List<JobExecution> holders = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            String key = freshKey();
            keys.add(key);
            JobExecution holder = execution(HANDLER, "default", key);
            holders.add(holder);
            setUp = setUp.compose(x -> repository.save(holder)).mapEmpty();
        }

        // When: each finish and each save are started together
        setUp.compose(x -> {
                    for (int i = 0; i < holders.size(); i++) {
                        finishes.add(forceState(holders.get(i).id(), JobState.SUCCEEDED));
                        saves.add(repository.save(execution(HANDLER, "default", keys.get(i))));
                    }
                    return Future.all(saves);
                })
                .compose(all -> {
                    UUID[] ids = all.<UUID>list().toArray(UUID[]::new);
                    return pool.preparedQuery("SELECT count(*) AS n FROM job_executions WHERE id = ANY($1)")
                            .execute(Tuple.of(ids))
                            .map(rows -> List.of(rows.iterator().next().getLong("n"), (long) ids.length));
                })
                .onSuccess(counts -> ctx.verify(() -> {
                    // Then: no save failed and every returned id is a stored execution
                    assertEquals(counts.get(1), counts.get(0));
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("a save that finds the key held does not abort the caller's transaction")
    void duplicateInsideTransactionKeepsTheTransactionUsable(VertxTestContext ctx) {
        // Given: a held key
        String key = freshKey();
        JobExecution holder = execution(HANDLER, "default", key);
        JobExecution duplicate = execution(HANDLER, "default", key);

        // When: a transaction saves the duplicate and then runs another statement
        repository
                .save(holder)
                .compose(x -> pool.withTransaction(
                        conn -> repository.save(duplicate, conn).compose(id -> conn.preparedQuery("SELECT 1 AS one")
                                .execute()
                                .map(rows -> id))))
                .onSuccess(id -> ctx.verify(() -> {
                    // Then: the transaction committed and the holder was returned
                    assertEquals(holder.id(), id);
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("a duplicate leaves the holder's row untouched, including updated_at")
    void duplicateDoesNotTouchTheHolder(VertxTestContext ctx) {
        String key = freshKey();
        JobExecution holder = execution(HANDLER, "default", key);
        repository
                .save(holder)
                .compose(x -> updatedAt(holder.id()))
                .compose(before -> repository
                        .save(execution(HANDLER, "default", key))
                        .compose(x -> updatedAt(holder.id()))
                        .map(after -> List.of(before, after)))
                .onSuccess(times -> ctx.verify(() -> {
                    assertEquals(times.get(0), times.get(1));
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    private static Future<Object> updatedAt(UUID id) {
        return pool.preparedQuery("SELECT updated_at FROM job_executions WHERE id = $1")
                .execute(Tuple.of(id))
                .map(rows -> rows.iterator().next().getValue("updated_at"));
    }
}
