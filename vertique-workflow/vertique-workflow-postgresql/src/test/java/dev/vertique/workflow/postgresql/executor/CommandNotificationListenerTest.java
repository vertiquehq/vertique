// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.vertx.core.Vertx;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgConnection;
import io.vertx.sqlclient.Pool;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies how the listener treats a connection that finishes its {@code LISTEN} after it already closed. */
class CommandNotificationListenerTest {

    private Vertx vertx;
    private CommandNotificationListener listener;
    private AutoCloseable registration;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        // Nothing listens on port 1, so the listener's own attempt fails and leaves it active with no connection.
        PgConnectOptions options = new PgConnectOptions().setHost("127.0.0.1").setPort(1);
        listener = new CommandNotificationListener(vertx, options, mock(Pool.class), Set.of(), 3_600_000, 3_600_000);
        var registered = listener.register(vertx.getOrCreateContext(), availableAt -> {});
        registration = registered::close;
    }

    @AfterEach
    void tearDown() throws Exception {
        registration.close();
        vertx.close().toCompletionStage().toCompletableFuture().get();
    }

    @Test
    @DisplayName("a connection that closed before it was stored is not kept")
    void connectionClosedBeforeItWasStoredIsNotKept() {
        PgConnection conn = mock(PgConnection.class);

        boolean kept = listener.opened(1, conn, new AtomicBoolean(true));

        assertThat(kept).isFalse();
        assertThat(listener.connected()).isFalse();
    }

    @Test
    @DisplayName("a connection that is still open is kept")
    void connectionStillOpenIsKept() {
        PgConnection conn = mock(PgConnection.class);

        boolean kept = listener.opened(1, conn, new AtomicBoolean(false));

        assertThat(kept).isTrue();
        assertThat(listener.connected()).isTrue();
    }
}
