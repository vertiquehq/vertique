// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.postgresql;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.exception.ConnectionException;
import dev.vertique.db.exception.QueryTimeoutException;
import dev.vertique.db.test.PostgresContainer;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Tuple;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Failures of a live pool against a real PostgreSQL instance, translated by {@link
 * PgDbExceptionMapper}: a connection lost while a statement runs surfaces as {@link
 * ConnectionException}, and an exhausted pool stays {@link QueryTimeoutException}.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class PgConnectionLossIT {

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

    private static DbPoolConfig.DbPoolConfigBuilder configFor(String host, int port) {
        DbPoolConfig base = db.toPoolConfig();
        return DbPoolConfig.builder()
                .host(host)
                .port(port)
                .database(base.database())
                .user(base.user())
                .password(base.password());
    }

    @Test
    @DisplayName("a connection lost while a statement runs surfaces as ConnectionException")
    void shouldMapConnectionLostMidStatement() throws Exception {
        DbPoolConfig direct = db.toPoolConfig();
        try (ServerSocket proxy = new ServerSocket(0)) {
            List<Socket> sockets = new CopyOnWriteArrayList<>();
            Thread.ofPlatform().daemon().start(() -> forward(proxy, direct, sockets));
            Pool pool = DbPostgresqlModule.pgPool(
                    vertx, configFor("127.0.0.1", proxy.getLocalPort()).build(), Optional.empty());

            CompletableFuture<?> running = pool.query("select pg_sleep(30)")
                    .execute()
                    .toCompletionStage()
                    .toCompletableFuture();
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                while (sockets.size() < 2 && System.nanoTime() < deadline) {
                    Thread.sleep(50);
                }
                Thread.sleep(500);
                for (Socket socket : sockets) {
                    socket.close();
                }

                ExecutionException failure =
                        assertThrows(ExecutionException.class, () -> running.get(20, TimeUnit.SECONDS));
                assertInstanceOf(ConnectionException.class, mapper.translate(failure.getCause(), "query"));
            } finally {
                pool.close();
            }
        }
    }

    @Test
    @DisplayName("an exhausted pool surfaces as QueryTimeoutException, not ConnectionException")
    void shouldKeepPoolExhaustionAsQueryTimeout() throws Exception {
        DbPoolConfig direct = db.toPoolConfig();
        Pool pool = DbPostgresqlModule.pgPool(
                vertx,
                configFor(direct.host(), direct.port())
                        .maxPoolSize(1)
                        .connectionTimeoutMs(3_000)
                        .build(),
                Optional.empty());
        Pool observer = DbPostgresqlModule.pgPool(
                vertx, configFor(direct.host(), direct.port()).build(), Optional.empty());
        try {
            // Open the pool's only connection first: its establishment counts against the wait for a connection.
            pool.query("select 1")
                    .execute()
                    .toCompletionStage()
                    .toCompletableFuture()
                    .get(20, TimeUnit.SECONDS);
            CompletableFuture<?> holder = pool.query("select pg_sleep(6)")
                    .execute()
                    .toCompletionStage()
                    .toCompletableFuture();
            awaitRunning(observer, holder, "select pg_sleep(6)");

            ExecutionException failure = assertThrows(ExecutionException.class, () -> pool.query("select 1")
                    .execute()
                    .toCompletionStage()
                    .toCompletableFuture()
                    .get(20, TimeUnit.SECONDS));
            assertInstanceOf(QueryTimeoutException.class, mapper.translate(failure.getCause(), "query"));
            holder.get(20, TimeUnit.SECONDS);
        } finally {
            pool.close();
            observer.close();
        }
    }

    private static void awaitRunning(Pool observer, CompletableFuture<?> holder, String statement) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            var rows = observer.preparedQuery(
                            "select count(*) from pg_stat_activity where state = 'active' and query = $1")
                    .execute(Tuple.of(statement))
                    .toCompletionStage()
                    .toCompletableFuture()
                    .get(10, TimeUnit.SECONDS);
            if (rows.iterator().next().getLong(0) > 0) {
                return;
            }
            if (holder.isDone()) {
                holder.get();
                throw new AssertionError("the statement finished before it could be observed: " + statement);
            }
            Thread.sleep(50);
        }
        throw new AssertionError("the statement never became active: " + statement);
    }

    private static void forward(ServerSocket proxy, DbPoolConfig target, List<Socket> sockets) {
        try {
            while (true) {
                Socket client = proxy.accept();
                Socket server = new Socket(target.host(), target.port());
                sockets.add(client);
                sockets.add(server);
                pipe(client, server);
                pipe(server, client);
            }
        } catch (IOException closed) {
            // the proxy socket was closed by the test
        }
    }

    private static void pipe(Socket from, Socket to) {
        Thread.ofPlatform().daemon().start(() -> {
            try (InputStream in = from.getInputStream();
                    OutputStream out = to.getOutputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                    out.flush();
                }
            } catch (IOException closed) {
                // one side closed; the other side is closed by the test
            }
        });
    }
}
