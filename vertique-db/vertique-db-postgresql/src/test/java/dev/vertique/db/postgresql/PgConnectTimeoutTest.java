// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.vertique.db.DbPoolConfig;
import io.netty.channel.ConnectTimeoutException;
import io.vertx.core.Vertx;
import io.vertx.core.net.NetClientOptions;
import io.vertx.sqlclient.Pool;
import java.net.ServerSocket;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Checks that the pool built by {@link DbPostgresqlModule} applies {@code connectTimeoutMs} to the
 * TCP connect and leaves the reconnect settings of the connect options in force. Needs no database.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class PgConnectTimeoutTest {

    private static Vertx vertx;

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
    @DisplayName("connectTimeoutMs bounds the TCP connect")
    void shouldBoundTheTcpConnect() throws Exception {
        long started = System.nanoTime();
        Throwable failure =
                failureOf(config("10.255.255.1", 5432).connectTimeoutMs(500).build());
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        // A network that rejects the address at once (no route) cannot show a timeout.
        assumeTrue(failure instanceof ConnectTimeoutException, "no black-holed address on this network: " + failure);
        assertTrue(elapsedMs < 20_000, "the connect must stop near connectTimeoutMs, took " + elapsedMs + " ms");
    }

    @Test
    @DisplayName("the pool applies connectTimeoutMs to the transport options")
    void shouldCarryTheConfiguredConnectTimeout() {
        DbPoolConfig config = config("h", 5432).connectTimeoutMs(1234).build();
        NetClientOptions options = DbPostgresqlModule.netClientOptions(config);
        assertEquals(1234, options.getConnectTimeout());
        assertEquals(
                60_000,
                DbPostgresqlModule.netClientOptions(config("h", 5432).build()).getConnectTimeout());
    }

    @Test
    @DisplayName("transport options do not displace the reconnect settings of the connect options")
    void shouldKeepReconnectSettings() throws Exception {
        long started = System.nanoTime();
        failureOf(config("127.0.0.1", unusedPort())
                .reconnectAttempts(2)
                .reconnectIntervalMs(400)
                .build());
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertTrue(elapsedMs >= 700, "two reconnects 400 ms apart take at least 800 ms, took " + elapsedMs + " ms");
    }
}
