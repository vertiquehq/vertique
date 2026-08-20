// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vertique.mcp.lifecycle.McpRequestCompletedEvent;
import dev.vertique.mcp.lifecycle.McpRequestCompletedListener;
import dev.vertique.mcp.lifecycle.McpRequestLifecycleObserver;
import dev.vertique.mcp.lifecycle.McpRequestObservation;
import dev.vertique.mcp.lifecycle.McpRequestTerminalObservation;
import dev.vertique.rest.core.config.HttpConfig;
import dev.vertique.rest.security.IdentityResolutionMiddleware;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.handler.BodyHandler;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Exercises neutral lifecycle extensions through a real MCP discovery request.
 *
 * <p>The client is a {@link WebClient} wrapping a raw {@link HttpClient}: {@code WebClient}
 * aggregates the body before its future resolves, while the wrapped raw client keeps the awaitable
 * {@code close()} this test needs because it owns its {@link Vertx}. The raw client never issues a
 * request itself.
 */
class McpLifecycleObserverCompositionTest {
    private Vertx vertx;
    private HttpServer server;
    private HttpClient rawClient;
    private WebClient client;

    /**
     * Joins the server and raw-client closes, then closes the owned {@link Vertx} from the join
     * callback — including {@code vertx.close()} in the join races the event-loop shutdown.
     *
     * @throws Exception if teardown does not complete within its bound
     */
    @AfterEach
    void closeVertx() throws Exception {
        CompletableFuture<Void> closed = new CompletableFuture<>();
        Future<Void> serverClose = server != null ? server.close() : Future.succeededFuture();
        Future<Void> clientClose = rawClient != null ? rawClient.close() : Future.succeededFuture();
        Future.join(serverClose, clientClose).onComplete(ignored -> {
            if (vertx == null) {
                closed.complete(null);
                return;
            }
            vertx.close().onComplete(result -> closed.complete(null));
        });
        closed.get(5, TimeUnit.SECONDS);
        server = null;
        rawClient = null;
        client = null;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("compositionRows")
    @DisplayName("composes neutral observers and completion listeners without shared request state")
    void shouldEnforceT001ContractMatrix(
            String row,
            Set<McpRequestLifecycleObserver> observers,
            Set<McpRequestCompletedListener> listeners,
            List<RecordingObserver> healthyObservers,
            List<RecordingListener> healthyListeners)
            throws Exception {
        vertx = Vertx.vertx();
        int port = mountDiscoveryServer(observers, listeners);

        JsonObject response = discover(port);

        assertThat(response.getJsonObject("result")
                        .getJsonObject("_meta")
                        .getJsonObject("io.modelcontextprotocol/serverInfo")
                        .getString("name"))
                .isEqualTo("lifecycle-test");
        for (RecordingObserver healthyObserver : healthyObservers) {
            healthyObserver.awaitCallbacks();
        }
        for (RecordingListener healthyListener : healthyListeners) {
            healthyListener.awaitCallback();
        }
        healthyObservers.forEach(RecordingObserver::assertExactlyOneTerminalAndCompletionOnOneContext);
        healthyListeners.forEach(RecordingListener::assertExactlyOneCompletionOnOwningContext);
    }

    private static Stream<Arguments> compositionRows() {
        RecordingObserver firstObserver = new RecordingObserver();
        RecordingObserver secondObserver = new RecordingObserver();
        RecordingListener firstListener = new RecordingListener();
        RecordingListener secondListener = new RecordingListener();
        RecordingListener thirdListener = new RecordingListener();
        RecordingListener fourthListener = new RecordingListener();
        return Stream.of(
                Arguments.of("shouldBootDiscoveryWithZeroOptionalExtensions", Set.of(), Set.of(), List.of(), List.of()),
                Arguments.of(
                        "shouldCompileAndRunWithZeroOrMultipleObserverContributions",
                        Set.of(firstObserver, secondObserver, throwingObserver(), nullObserver()),
                        Set.of(firstListener, secondListener, throwingListener()),
                        List.of(firstObserver, secondObserver),
                        List.of(firstListener, secondListener)),
                Arguments.of(
                        "shouldCompileAndRunWithZeroOrMultipleCompletionListeners",
                        Set.of(),
                        Set.of(thirdListener, fourthListener),
                        List.of(),
                        List.of(thirdListener, fourthListener)));
    }

    private int mountDiscoveryServer(
            Set<McpRequestLifecycleObserver> observers, Set<McpRequestCompletedListener> listeners) throws Exception {
        McpServerConfig config = McpServerConfig.builder()
                .enabled(true)
                .serverName("lifecycle-test")
                .serverVersion("1.0.0")
                .build();
        IdentityResolutionMiddleware identity = mock(IdentityResolutionMiddleware.class);
        when(identity.handlerFor(org.mockito.ArgumentMatchers.any())).thenReturn(context -> context.next());
        McpRouterMount mount = new McpRouterMount(
                config,
                new McpServerConfigValidator(),
                new McpRequestDispatcher(config, observers, listeners),
                Set.of(),
                identity,
                HttpConfig.builder().build());
        Router router = Router.router(vertx);
        router.route().handler(BodyHandler.create());
        router.route(config.mountPath()).subRouter(await(mount.createRouter(vertx)));
        server = await(vertx.createHttpServer().requestHandler(router).listen(0, "127.0.0.1"));
        return server.actualPort();
    }

    private JsonObject discover(int port) throws Exception {
        JsonObject request = new JsonObject().put("jsonrpc", "2.0").put("id", 1).put("method", "server/discover");
        rawClient = vertx.createHttpClient();
        client = WebClient.wrap(rawClient);
        var response = await(client.post(port, "127.0.0.1", "/mcp/")
                .putHeader("content-type", "application/json")
                .sendBuffer(request.toBuffer()));
        return response.bodyAsJsonObject();
    }

    private static McpRequestLifecycleObserver throwingObserver() {
        return startedAt -> {
            throw new IllegalStateException("synthetic observer failure");
        };
    }

    private static McpRequestLifecycleObserver nullObserver() {
        return startedAt -> null;
    }

    private static McpRequestCompletedListener throwingListener() {
        return event -> {
            throw new IllegalStateException("synthetic listener failure");
        };
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private static final class RecordingObserver implements McpRequestLifecycleObserver, McpRequestObservation {
        private final CountDownLatch callbacks = new CountDownLatch(2);
        private int terminalCount;
        private int completionCount;
        private Context terminalContext;
        private Context completionContext;

        @Override
        public McpRequestObservation open(java.time.Instant startedAt) {
            return this;
        }

        @Override
        public void onTerminal(McpRequestTerminalObservation observation) {
            terminalCount++;
            terminalContext = Vertx.currentContext();
            callbacks.countDown();
        }

        @Override
        public void onCompleted(McpRequestCompletedEvent event) {
            completionCount++;
            completionContext = Vertx.currentContext();
            callbacks.countDown();
        }

        void awaitCallbacks() throws InterruptedException {
            assertThat(callbacks.await(5, TimeUnit.SECONDS)).isTrue();
        }

        void assertExactlyOneTerminalAndCompletionOnOneContext() {
            assertThat(terminalCount).isOne();
            assertThat(completionCount).isOne();
            assertThat(terminalContext).isNotNull().isSameAs(completionContext);
        }
    }

    private static final class RecordingListener implements McpRequestCompletedListener {
        private final CountDownLatch callbacks = new CountDownLatch(1);
        private int completionCount;
        private Context completionContext;

        @Override
        public void onCompleted(McpRequestCompletedEvent event) {
            completionCount++;
            completionContext = Vertx.currentContext();
            callbacks.countDown();
        }

        void awaitCallback() throws InterruptedException {
            assertThat(callbacks.await(5, TimeUnit.SECONDS)).isTrue();
        }

        void assertExactlyOneCompletionOnOwningContext() {
            assertThat(completionCount).isOne();
            assertThat(completionContext).isNotNull();
        }
    }
}
