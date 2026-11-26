// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.application.VertiqueApplicationBootstrap;
import dev.vertique.application.VertiqueApplicationHandle;
import dev.vertique.application.VertiqueRuntime;
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
 * Holds startup twice, and checks what the readiness probe says in each window: inside the edge phase, after the
 * management server is serving and before the API listener binds, and inside the post-start phase, after the API
 * listener is bound and before startup has finished.
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

    private record Probe(int status, String body) {}

    private static Probe ready(HttpClient client, int managementPort) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + managementPort + "/health/ready"))
                .timeout(Duration.ofSeconds(5))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        return new Probe(response.statusCode(), response.body());
    }

    @Test
    @DisplayName("readiness is not UP while the API listener is unbound or startup is unfinished")
    void notReadyBeforeApiListensOrStartupFinishes() throws Exception {
        HoldEdgeModule.Holds.reset();
        HoldEdgeModule.Hold edge = HoldEdgeModule.Holds.edge;
        HoldEdgeModule.Hold afterStart = HoldEdgeModule.Holds.afterStart;
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

            assertTrue(awaitOrFailure(edge, started), "startup reached the edge phase");
            Probe inEdge = ready(client, managementPort);
            assertFalse(accepts(httpPort), "the API listener binds only after the edge phase starts");
            assertEquals(503, inEdge.status(), "readiness while the API port refuses connections");
            assertTrue(inEdge.body().contains("\"startup\""), inEdge.body());

            edge.release.complete();
            assertTrue(awaitOrFailure(afterStart, started), "startup reached the post-start phase");
            Probe inAfterStart = ready(client, managementPort);
            assertTrue(accepts(httpPort), "every verticle is deployed before the post-start phase");
            assertEquals(
                    503, inAfterStart.status(), "readiness waits for startup to finish, not only for the listener");
            assertTrue(inAfterStart.body().contains("\"startup\""), inAfterStart.body());

            afterStart.release.complete();
            VertiqueApplicationHandle<HeldEdgeComponent> handle =
                    started.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);

            assertTrue(accepts(httpPort), "the API listener is bound once startup completes");
            assertEquals(200, ready(client, managementPort).status());

            handle.shutdown().toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
        } finally {
            edge.release.tryComplete();
            afterStart.release.tryComplete();
            vertx.close().toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
        }
    }

    /** Waits for a hold to be reached, giving up early when startup has already failed. */
    private static boolean awaitOrFailure(HoldEdgeModule.Hold hold, Future<?> started) throws InterruptedException {
        for (int i = 0; i < 300; i++) {
            if (hold.reached.await(100, TimeUnit.MILLISECONDS)) {
                return true;
            }
            if (started.failed()) {
                throw new AssertionError("startup failed before the hold", started.cause());
            }
        }
        return false;
    }
}
