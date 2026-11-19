// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.PoolConnectHandler;
import dev.vertique.db.exception.QueryTimeoutException;
import dev.vertique.db.test.PostgresContainer;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.Pool;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * What a {@link PoolConnectHandler} does to the pool, against a real PostgreSQL instance: the pool
 * closes the connection when the handler's future completes, whether it succeeded or failed, and a
 * handler that never completes keeps its connection out of the pool.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class PgConnectHandlerIT {

    static final PostgresContainer db = new PostgresContainer();
    static Vertx vertx;

    private final PgDbExceptionMapper mapper = new PgDbExceptionMapper();

    @BeforeAll
    static void setUp() {
        db.start();
        vertx = Vertx.vertx();
    }

    @AfterAll
    static void tearDown() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        db.close();
    }

    private static DbPoolConfig.DbPoolConfigBuilder config() {
        DbPoolConfig base = db.toPoolConfig();
        return DbPoolConfig.builder()
                .host(base.host())
                .port(base.port())
                .database(base.database())
                .user(base.user())
                .password(base.password());
    }

    private static Object selectOne(Pool pool) throws Exception {
        return pool.query("select 1")
                .execute()
                .toCompletionStage()
                .toCompletableFuture()
                .get(20, TimeUnit.SECONDS)
                .iterator()
                .next()
                .getInteger(0);
    }

    @Test
    @DisplayName("a handler that completes admits the connection with its session settings in force")
    void shouldAdmitAfterSuccess() throws Exception {
        PoolConnectHandler handler = conn -> conn.query("set application_name = 'connect-handler-it'")
                .execute()
                .mapEmpty();
        Pool pool = DbPostgresqlModule.pgPool(vertx, config().maxPoolSize(1).build(), Optional.of(handler));
        try {
            Object name = pool.query("show application_name")
                    .execute()
                    .toCompletionStage()
                    .toCompletableFuture()
                    .get(20, TimeUnit.SECONDS)
                    .iterator()
                    .next()
                    .getString(0);
            assertEquals("connect-handler-it", name);
        } finally {
            pool.close();
        }
    }

    @Test
    @DisplayName("a handler whose future fails still admits the connection to the pool")
    void shouldAdmitAfterFailure() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        PoolConnectHandler handler = conn -> {
            calls.incrementAndGet();
            return Future.failedFuture(new IllegalStateException("handler failed"));
        };
        Pool pool = DbPostgresqlModule.pgPool(vertx, config().maxPoolSize(1).build(), Optional.of(handler));
        try {
            assertEquals(1, selectOne(pool));
            assertTrue(calls.get() >= 1, "the handler runs for the new connection");
        } finally {
            pool.close();
        }
    }

    @Test
    @DisplayName("a handler that never completes keeps its connection out of the pool, and callers time out")
    void shouldStarveTheWaitersWhileTheHandlerHangs() throws Exception {
        Promise<Void> never = Promise.promise();
        AtomicInteger calls = new AtomicInteger();
        PoolConnectHandler handler = conn -> {
            calls.incrementAndGet();
            return never.future();
        };
        Pool pool = DbPostgresqlModule.pgPool(
                vertx, config().maxPoolSize(1).connectionTimeoutMs(1_500).build(), Optional.of(handler));
        try {
            ExecutionException failure = assertThrows(ExecutionException.class, () -> selectOne(pool));
            assertInstanceOf(QueryTimeoutException.class, mapper.translate(failure.getCause(), "query"));
            assertEquals(1, calls.get(), "the one slot of the pool stays taken by the connection in the handler");

            // Once the handler completes, the connection is admitted and the pool serves callers.
            never.complete();
            assertEquals(1, selectOne(pool));
        } finally {
            pool.close();
        }
    }
}
