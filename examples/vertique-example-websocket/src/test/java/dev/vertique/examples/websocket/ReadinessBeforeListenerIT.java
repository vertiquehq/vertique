// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.application.VertiqueApplicationBootstrap;
import dev.vertique.application.VertiqueApplicationHandle;
import dev.vertique.core.VertiqueRuntime;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Holds startup inside the edge phase, after the management server is serving and before the API listener binds,
 * and checks what the readiness probe says in that window.
 *
 * <p>The management server deploys in the infrastructure phase and the API server in the edge phase. A readiness
 * probe that answers 200 while the API port still refuses connections sends traffic to a process that cannot serve it.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ReadinessBeforeListenerIT {

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static boolean accepts(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
            return true;
        } catch (IOException refused) {
            return false;
        }
    }

    private static int readyStatus(HttpClient client, int managementPort) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + managementPort + "/health/ready"))
                .timeout(Duration.ofSeconds(5))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @Test
    @DisplayName("readiness does not report 200 while the API listener is not bound")
    void notReadyBeforeApiListens() throws Exception {
        HoldEdgeModule.Hold.reset();
        int httpPort = freePort();
        int managementPort = freePort();
        JsonObject config = new JsonObject()
                .put("http", new JsonObject().put("port", httpPort).put("host", "127.0.0.1"))
                .put("management", new JsonObject().put("port", managementPort).put("host", "127.0.0.1"));
        Vertx vertx = Vertx.vertx();
        HttpClient client = HttpClient.newHttpClient();
        try {
            Future<VertiqueApplicationHandle<HeldEdgeComponent>> started = VertiqueApplicationBootstrap.start(
                    VertiqueRuntime.of(vertx, config), new HeldEdgeComponentVertiqueComponentFactory());

            assertTrue(HoldEdgeModule.Hold.reached.await(30, TimeUnit.SECONDS), "startup reached the edge phase");

            int held = readyStatus(client, managementPort);
            assertFalse(accepts(httpPort), "the API listener binds only after the edge phase starts");
            assertNotEquals(200, held, "readiness while the API port refuses connections");

            HoldEdgeModule.Hold.release.complete();
            VertiqueApplicationHandle<HeldEdgeComponent> handle =
                    started.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);

            assertTrue(accepts(httpPort), "the API listener is bound once startup completes");
            assertEquals(200, readyStatus(client, managementPort));

            handle.shutdown().toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
        } finally {
            HoldEdgeModule.Hold.release.tryComplete();
            vertx.close().toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
        }
    }
}
