// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.postgresql;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.exception.ConnectionException;
import io.netty.channel.ConnectTimeoutException;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.ClosedConnectionException;
import io.vertx.sqlclient.Pool;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.SocketException;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Drives the pool built by {@link DbPostgresqlModule} against sockets that refuse, reset or never
 * answer, and checks the failure the client really throws, translated by {@link PgDbExceptionMapper},
 * together with the TCP connect timeout setting. Needs no database.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class PgConnectFailureTest {

    private static Vertx vertx;

    private final PgDbExceptionMapper mapper = new PgDbExceptionMapper();

    @BeforeAll
    static void startVertx() {
        vertx = Vertx.vertx();
    }

    @AfterAll
    static void stopVertx() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private static DbPoolConfig.DbPoolConfigBuilder config(String host, int port) {
        return DbPoolConfig.builder()
                .host(host)
                .port(port)
                .database("db")
                .user("u")
                .password("p");
    }

    private static int unusedPort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private Throwable failureOf(DbPoolConfig config) throws Exception {
        Pool pool = DbPostgresqlModule.pgPool(vertx, config, Optional.empty());
        try {
            pool.query("select 1")
                    .execute()
                    .toCompletionStage()
                    .toCompletableFuture()
                    .get(30, TimeUnit.SECONDS);
            throw new AssertionError("the query unexpectedly succeeded");
        } catch (ExecutionException e) {
            return e.getCause();
        } finally {
            pool.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("connection refused surfaces as ConnectionException")
    void shouldMapConnectionRefused() throws Exception {
        Throwable failure = failureOf(config("127.0.0.1", unusedPort()).build());
        assertInstanceOf(ConnectException.class, failure);
        assertInstanceOf(ConnectionException.class, mapper.translate(failure, "connect"));
    }

    @Test
    @DisplayName("a server that closes the socket on accept surfaces as ConnectionException")
    void shouldMapConnectionClosedByServer() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            Thread acceptor = Thread.ofPlatform().daemon().start(() -> {
                try {
                    while (true) {
                        server.accept().close();
                    }
                } catch (Exception closed) {
                    // the server socket was closed by the test
                }
            });
            Throwable failure =
                    failureOf(config("127.0.0.1", server.getLocalPort()).build());
            // Depending on the platform the close shows as a reset socket or as a closed connection.
            assertTrue(
                    failure instanceof SocketException || failure instanceof ClosedConnectionException,
                    "unexpected failure type: " + failure);
            assertInstanceOf(ConnectionException.class, mapper.translate(failure, "connect"));
            acceptor.interrupt();
        }
    }

    @Test
    @DisplayName("a TCP connect timeout surfaces as ConnectionException")
    void shouldMapTheConnectTimeout() throws Exception {
        Throwable failure =
                failureOf(config("10.255.255.1", 5432).connectTimeoutMs(500).build());

        // A network that rejects the address at once (no route) cannot show a timeout.
        assumeTrue(failure instanceof ConnectTimeoutException, "no black-holed address on this network: " + failure);
        assertInstanceOf(ConnectionException.class, mapper.translate(failure, "connect"));
    }
}
