// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dagger.BindsOptionalOf;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dagger.multibindings.Multibinds;
import dev.vertique.context.ContextRuntimeModule;
import dev.vertique.correlation.CorrelationContextModule;
import dev.vertique.mcp.lifecycle.McpRequestCompletedEvent;
import dev.vertique.mcp.lifecycle.McpRequestCompletedListener;
import dev.vertique.mcp.lifecycle.McpRequestLifecycleObserver;
import dev.vertique.mcp.lifecycle.McpRequestObservation;
import dev.vertique.mcp.lifecycle.McpRequestTerminalObservation;
import dev.vertique.rest.core.config.HttpConfig;
import dev.vertique.rest.core.middleware.RequestContextLifecycle;
import dev.vertique.rest.core.router.RouterMount;
import dev.vertique.rest.core.security.RouteAuthHandler;
import dev.vertique.rest.core.security.SecurityRuntime;
import dev.vertique.rest.security.IdentityResolutionMiddleware;
import dev.vertique.security.authz.Authorizer;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.handler.BodyHandler;
import jakarta.inject.Singleton;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Exercises neutral lifecycle extensions through a real MCP discovery request served by a mount that
 * a <em>generated</em> Dagger component composed.
 *
 * <p>Every row obtains its {@link RouterMount} from a test {@code @Component} that includes the
 * production {@link McpServerModule}, so the matrix proves the composed graph — the
 * {@code @Multibinds} zero-contribution case, and the multi-contribution case where each
 * {@code @IntoSet} observer/listener reaches the dispatcher — rather than a hand-assembled object
 * graph that could pass while the module's bindings are broken. {@link GraphExternalsModule} supplies
 * only the bindings an application graph owns outside this module (configuration, the
 * {@code Set<RouteAuthHandler>} that {@code AuthModule} declares in production, identity resolution,
 * the security runtime, and HTTP limits).
 *
 * <p>The client is a {@link WebClient} wrapping a raw {@link HttpClient}: {@code WebClient}
 * aggregates the body before its future resolves, while the wrapped raw client keeps the awaitable
 * {@code close()} this test needs because it owns its {@link Vertx}. The raw client never issues a
 * request itself.
 */
class McpLifecycleObserverCompositionTest {
    private static final String PROTOCOL_VERSION = "2026-07-28";

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
    void shouldEnforceT001ContractMatrix(String row, Composition composition) throws Exception {
        vertx = Vertx.vertx();
        int port = mountDiscoveryServer(composition.mount());

        JsonObject response = discover(port);

        assertThat(composition.composedObservers())
                .as("%s: the graph must compose exactly the contributed lifecycle observers", row)
                .hasSize(composition.expectedObserverCount())
                .containsAll(composition.healthyObservers());
        assertThat(composition.composedListeners())
                .as("%s: the graph must compose exactly the contributed completion listeners", row)
                .hasSize(composition.expectedListenerCount())
                .containsAll(composition.healthyListeners());
        assertThat(response.getJsonObject("result")
                        .getJsonObject("_meta")
                        .getJsonObject("io.modelcontextprotocol/serverInfo")
                        .getString("name"))
                .isEqualTo("lifecycle-test");
        for (RecordingObserver healthyObserver : composition.healthyObservers()) {
            healthyObserver.awaitCallbacks();
        }
        for (RecordingListener healthyListener : composition.healthyListeners()) {
            healthyListener.awaitCallback();
        }
        composition.healthyObservers().forEach(RecordingObserver::assertExactlyOneTerminalAndCompletionOnOneContext);
        composition.healthyListeners().forEach(RecordingListener::assertExactlyOneCompletionOnOwningContext);
    }

    /**
     * Given an application composes a root {@link BodyHandler} with uploads enabled ahead of the MCP
     * mount, when a multipart request reaches the mount, then the mount still rejects the request and
     * leaves no spooled upload file behind once the response has settled.
     *
     * @param uploadsDirectory the ancestor handler's spool directory, owned by JUnit
     * @throws Exception if the exchange or the bounded cleanup wait does not complete
     */
    @Test
    @DisplayName("deletes the uploads an application-composed ancestor BodyHandler spooled")
    void shouldDeleteAncestorSpooledUploadsAfterRequestEnd(@TempDir Path uploadsDirectory) throws Exception {
        vertx = Vertx.vertx();
        List<Path> spooled = new CopyOnWriteArrayList<>();
        int port = mountServer(
                zeroExtensionMount(), BodyHandler.create().setUploadsDirectory(uploadsDirectory.toString()), spooled);

        HttpResponse<Buffer> response = postMultipart(port);

        assertThat(spooled)
                .as("the ancestor BodyHandler must spool the multipart part before any MCP handler runs")
                .isNotEmpty();
        assertThat(response.statusCode())
                .as("a multipart body is an MCP protocol rejection, not a discovery result")
                .isBetween(400, 499);
        awaitSpooledUploadsDeleted(uploadsDirectory, spooled);
    }

    /**
     * Builds one generated Dagger graph per row: a zero-contribution graph whose observer and listener
     * sets can only come from {@link McpServerModule}'s {@code @Multibinds} declarations, and two
     * multi-contribution graphs whose sets are assembled from {@code @Provides @IntoSet} bindings.
     */
    private static Stream<Arguments> compositionRows() {
        RecordingObserver firstObserver = new RecordingObserver();
        RecordingObserver secondObserver = new RecordingObserver();
        RecordingListener firstListener = new RecordingListener();
        RecordingListener secondListener = new RecordingListener();
        RecordingListener thirdListener = new RecordingListener();
        RecordingListener fourthListener = new RecordingListener();

        ZeroExtensionComponent zeroExtension = zeroExtensionComponent();
        ObserverAndListenerComponent observersAndListeners =
                DaggerMcpLifecycleObserverCompositionTest_ObserverAndListenerComponent.builder()
                        .observerAndListenerContributions(new ObserverAndListenerContributions(
                                firstObserver, secondObserver, firstListener, secondListener))
                        .build();
        ListenerOnlyComponent listenersOnly = DaggerMcpLifecycleObserverCompositionTest_ListenerOnlyComponent.builder()
                .listenerOnlyContributions(new ListenerOnlyContributions(thirdListener, fourthListener))
                .build();

        return Stream.of(
                Arguments.of(
                        "shouldBootDiscoveryWithZeroOptionalExtensions",
                        new Composition(
                                soleMount(zeroExtension.routerMounts()),
                                zeroExtension.lifecycleObservers(),
                                zeroExtension.completedListeners(),
                                0,
                                0,
                                List.of(),
                                List.of())),
                Arguments.of(
                        "shouldCompileAndRunWithZeroOrMultipleObserverContributions",
                        new Composition(
                                soleMount(observersAndListeners.routerMounts()),
                                observersAndListeners.lifecycleObservers(),
                                observersAndListeners.completedListeners(),
                                4,
                                3,
                                List.of(firstObserver, secondObserver),
                                List.of(firstListener, secondListener))),
                Arguments.of(
                        "shouldCompileAndRunWithZeroOrMultipleCompletionListeners",
                        new Composition(
                                soleMount(listenersOnly.routerMounts()),
                                listenersOnly.lifecycleObservers(),
                                listenersOnly.completedListeners(),
                                0,
                                2,
                                List.of(),
                                List.of(thirdListener, fourthListener))));
    }

    private static ZeroExtensionComponent zeroExtensionComponent() {
        return DaggerMcpLifecycleObserverCompositionTest_ZeroExtensionComponent.create();
    }

    private static RouterMount zeroExtensionMount() {
        return soleMount(zeroExtensionComponent().routerMounts());
    }

    /** Asserts the module contributes exactly one mount and returns it. */
    private static RouterMount soleMount(Set<RouterMount> mounts) {
        assertThat(mounts)
                .as("McpServerModule must contribute exactly one RouterMount to the graph")
                .hasSize(1);
        return mounts.iterator().next();
    }

    private int mountDiscoveryServer(RouterMount mount) throws Exception {
        return mountServer(mount, BodyHandler.create(), new CopyOnWriteArrayList<>());
    }

    /**
     * Mounts the graph-composed MCP sub-router behind an application-composed root
     * {@code bodyHandler}, recording the paths that handler spooled so a test can prove cleanup rather
     * than absence of uploads.
     */
    private int mountServer(RouterMount mount, BodyHandler rootBodyHandler, List<Path> spooledUploads)
            throws Exception {
        Router router = Router.router(vertx);
        // R09: production composes RequestContextLifecycle as a ROOT-scoped middleware ahead of every
        // RouterMount sub-router (HttpVerticle); this hand-assembled router must install it too, or
        // McpRequestDispatcher#begin's correlation binding has no lifecycle handle to register with.
        router.route().handler(new RequestContextLifecycle());
        router.route().handler(rootBodyHandler);
        router.route().handler(context -> {
            context.fileUploads().forEach(upload -> spooledUploads.add(Path.of(upload.uploadedFileName())));
            context.next();
        });
        router.route(mount.mountPath()).subRouter(await(mount.createRouter(vertx)));
        server = await(vertx.createHttpServer().requestHandler(router).listen(0, "127.0.0.1"));
        return server.actualPort();
    }

    private JsonObject discover(int port) throws Exception {
        JsonObject request = new JsonObject()
                .put("jsonrpc", "2.0")
                .put("id", 1)
                .put("method", "server/discover")
                .put("params", discoverParams());
        rawClient = vertx.createHttpClient();
        client = WebClient.wrap(rawClient);
        var response = await(client.post(port, "127.0.0.1", "/mcp/")
                .putHeader("content-type", "application/json")
                .putHeader("MCP-Protocol-Version", PROTOCOL_VERSION)
                .putHeader("Mcp-Method", "server/discover")
                .putHeader("Mcp-Name", "server/discover")
                .sendBuffer(request.toBuffer()));
        return response.bodyAsJsonObject();
    }

    /**
     * Builds a schema-valid {@code params._meta} for a {@code server/discover} frame, carrying the
     * candidate protocol version and an empty client-capabilities object — both members are required
     * by the vendored {@code RequestMetaObject} definition of {@code mcp/schema/2026-07-28}.
     */
    private static JsonObject discoverParams() {
        return new JsonObject()
                .put(
                        "_meta",
                        new JsonObject()
                                .put("io.modelcontextprotocol/protocolVersion", PROTOCOL_VERSION)
                                .put("io.modelcontextprotocol/clientCapabilities", new JsonObject()));
    }

    /** Posts a multipart body carrying one file part, which the MCP contract never accepts. */
    private HttpResponse<Buffer> postMultipart(int port) throws Exception {
        String boundary = "vertique-mcp-boundary";
        Buffer multipartBody = Buffer.buffer()
                .appendString("--" + boundary + "\r\n")
                .appendString(
                        "Content-Disposition: form-data; name=\"payload\";" + " filename=\"ancestor-upload.bin\"\r\n")
                .appendString("Content-Type: application/octet-stream\r\n\r\n")
                .appendString("uploaded-bytes\r\n")
                .appendString("--" + boundary + "--\r\n");
        rawClient = vertx.createHttpClient();
        client = WebClient.wrap(rawClient);
        return await(client.post(port, "127.0.0.1", "/mcp/")
                .putHeader("content-type", "multipart/form-data; boundary=" + boundary)
                .sendBuffer(multipartBody));
    }

    /**
     * Waits, within a bounded deadline, for every recorded spool path and the whole directory to be
     * gone — the mount's cleanup runs off a routing-context end handler, so it settles after the
     * response the test already observed.
     */
    private static void awaitSpooledUploadsDeleted(Path uploadsDirectory, List<Path> spooled) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        List<Path> remaining = remainingUploads(uploadsDirectory);
        while (!remaining.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(25);
            remaining = remainingUploads(uploadsDirectory);
        }
        assertThat(remaining)
                .as("the MCP mount must delete every ancestor-spooled upload at request end")
                .isEmpty();
        assertThat(spooled).allSatisfy(path -> assertThat(Files.exists(path))
                .as("spooled upload %s must not survive the request", path)
                .isFalse());
    }

    private static List<Path> remainingUploads(Path uploadsDirectory) throws Exception {
        if (!Files.exists(uploadsDirectory)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(uploadsDirectory)) {
            return entries.toList();
        }
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

    // --- Matrix row model ---

    /**
     * One matrix row's graph-composed subject.
     *
     * @param mount the mount the generated component contributed
     * @param composedObservers the observer set the generated component resolved
     * @param composedListeners the listener set the generated component resolved
     * @param expectedObserverCount how many observers the row's bindings must compose
     * @param expectedListenerCount how many listeners the row's bindings must compose
     * @param healthyObservers the contributed observers that must each receive exactly one callback
     * @param healthyListeners the contributed listeners that must each receive exactly one callback
     */
    private record Composition(
            RouterMount mount,
            Set<McpRequestLifecycleObserver> composedObservers,
            Set<McpRequestCompletedListener> composedListeners,
            int expectedObserverCount,
            int expectedListenerCount,
            List<RecordingObserver> healthyObservers,
            List<RecordingListener> healthyListeners) {}

    // --- Test Dagger graphs ---

    /**
     * Provides the bindings an application graph owns outside {@link McpServerModule}.
     *
     * <p>The {@code Set<RouteAuthHandler>} declaration mirrors {@code AuthModule}'s production
     * {@code @Multibinds}; identity resolution and the security runtime are stubbed because this test
     * observes lifecycle composition, not identity. With identity resolution stubbed out no security
     * context is ever bound, so dispatch records no security facts.
     */
    @Module
    abstract static class GraphExternalsModule {
        private GraphExternalsModule() {}

        /** Declares the route-authentication set an application graph contributes into. */
        @Multibinds
        abstract Set<RouteAuthHandler> routeAuthHandlers();

        /**
         * Supplies the enabled MCP configuration every row's mount is validated against.
         *
         * @return the bounded discovery configuration used by the matrix
         */
        @Provides
        @Singleton
        static McpServerConfig config() {
            return McpServerConfig.builder()
                    .enabled(true)
                    .serverName("lifecycle-test")
                    .serverVersion("1.0.0")
                    .build();
        }

        /**
         * Supplies identity resolution that admits every request without binding a security context.
         *
         * @return a stub middleware whose handler simply advances the routing context
         */
        @Provides
        @Singleton
        static IdentityResolutionMiddleware identityResolutionMiddleware() {
            IdentityResolutionMiddleware identity = mock(IdentityResolutionMiddleware.class);
            when(identity.handlerFor(any())).thenReturn(context -> context.next());
            return identity;
        }

        /**
         * Supplies the security runtime the dispatcher snapshots from.
         *
         * @return a stub runtime that reports no established context
         */
        @Provides
        @Singleton
        static SecurityRuntime securityRuntime() {
            return mock(SecurityRuntime.class);
        }

        /**
         * Supplies the HTTP limits the mount applies to its own body handler.
         *
         * @return the framework default HTTP configuration
         */
        @Provides
        @Singleton
        static HttpConfig httpConfig() {
            // idleTimeoutSeconds armed: McpServerConfigValidator's startup gate (P04, issue W1) refuses
            // an enabled mount unless at least one HttpConfig liveness timeout is nonzero.
            return HttpConfig.builder().idleTimeoutSeconds(60).build();
        }

        /**
         * Supplies the MCP authorization enforcer as a stub, because this test observes lifecycle
         * composition, not tool authorization, and {@link McpServerModule}'s empty-by-default {@code
         * Set<McpToolInvoker>} multibinding already yields an empty registry that never calls it.
         *
         * @return a stub policy enforcer
         */
        @Provides
        @Singleton
        static McpPolicyEnforcer policyEnforcer() {
            return mock(McpPolicyEnforcer.class);
        }

        /**
         * Declares the optional core {@link dev.vertique.security.authz.Authorizer} binding {@link
         * McpServerModule#routerMount} now requires (issue #421 mount-time gate), mirroring {@code
         * AuthModule#optionalAuthorizer}. This graph never installs {@code AuthModule}, so the binding
         * resolves empty — correct here since {@code Set<McpToolInvoker>} is also empty, so no {@code
         * @RequiresAction} tool can ever trip the gate this test does not exercise.
         */
        @BindsOptionalOf
        abstract Authorizer optionalAuthorizer();
    }

    /**
     * The zero-extension graph: no {@code @IntoSet} observer or listener binding exists anywhere, so
     * both sets can only be satisfied by {@link McpServerModule}'s {@code @Multibinds} declarations.
     * Compiling this component is itself the proof that the zero-extension graph resolves.
     */
    @Singleton
    @Component(
            modules = {
                McpServerModule.class,
                GraphExternalsModule.class,
                ContextRuntimeModule.class,
                CorrelationContextModule.class
            })
    interface ZeroExtensionComponent {

        /**
         * Returns the mounts the module contributed.
         *
         * @return the router-mount multibinding, expected to hold exactly the MCP mount
         */
        Set<RouterMount> routerMounts();

        /**
         * Returns the composed lifecycle observers.
         *
         * @return the observer multibinding, expected to be empty
         */
        Set<McpRequestLifecycleObserver> lifecycleObservers();

        /**
         * Returns the composed completion listeners.
         *
         * @return the listener multibinding, expected to be empty
         */
        Set<McpRequestCompletedListener> completedListeners();
    }

    /** The multi-contribution graph: several observers and listeners, including failing contributions. */
    @Singleton
    @Component(
            modules = {
                McpServerModule.class,
                GraphExternalsModule.class,
                ContextRuntimeModule.class,
                CorrelationContextModule.class,
                ObserverAndListenerContributions.class
            })
    interface ObserverAndListenerComponent {

        /**
         * Returns the mounts the module contributed.
         *
         * @return the router-mount multibinding, expected to hold exactly the MCP mount
         */
        Set<RouterMount> routerMounts();

        /**
         * Returns the composed lifecycle observers.
         *
         * @return the observer multibinding, expected to hold the four contributed observers
         */
        Set<McpRequestLifecycleObserver> lifecycleObservers();

        /**
         * Returns the composed completion listeners.
         *
         * @return the listener multibinding, expected to hold the three contributed listeners
         */
        Set<McpRequestCompletedListener> completedListeners();
    }

    /** The listeners-only graph: zero observer contributions alongside several listeners. */
    @Singleton
    @Component(
            modules = {
                McpServerModule.class,
                GraphExternalsModule.class,
                ContextRuntimeModule.class,
                CorrelationContextModule.class,
                ListenerOnlyContributions.class
            })
    interface ListenerOnlyComponent {

        /**
         * Returns the mounts the module contributed.
         *
         * @return the router-mount multibinding, expected to hold exactly the MCP mount
         */
        Set<RouterMount> routerMounts();

        /**
         * Returns the composed lifecycle observers.
         *
         * @return the observer multibinding, expected to be empty
         */
        Set<McpRequestLifecycleObserver> lifecycleObservers();

        /**
         * Returns the composed completion listeners.
         *
         * @return the listener multibinding, expected to hold the two contributed listeners
         */
        Set<McpRequestCompletedListener> completedListeners();
    }

    /**
     * Contributes two recording observers, one throwing observer, one observer that opens no
     * observation, two recording listeners, and one throwing listener.
     */
    @Module
    static final class ObserverAndListenerContributions {
        private final RecordingObserver firstObserver;
        private final RecordingObserver secondObserver;
        private final RecordingListener firstListener;
        private final RecordingListener secondListener;

        ObserverAndListenerContributions(
                RecordingObserver firstObserver,
                RecordingObserver secondObserver,
                RecordingListener firstListener,
                RecordingListener secondListener) {
            this.firstObserver = firstObserver;
            this.secondObserver = secondObserver;
            this.firstListener = firstListener;
            this.secondListener = secondListener;
        }

        /**
         * Contributes the first healthy observer.
         *
         * @return the recording observer whose callbacks the row asserts
         */
        @Provides
        @IntoSet
        McpRequestLifecycleObserver first() {
            return firstObserver;
        }

        /**
         * Contributes the second healthy observer.
         *
         * @return the recording observer whose callbacks the row asserts
         */
        @Provides
        @IntoSet
        McpRequestLifecycleObserver second() {
            return secondObserver;
        }

        /**
         * Contributes an observer that throws when opened.
         *
         * @return the failing observer whose failure must be isolated
         */
        @Provides
        @IntoSet
        McpRequestLifecycleObserver failing() {
            return throwingObserver();
        }

        /**
         * Contributes an observer that opens no observation.
         *
         * @return the observer returning {@code null}, which must be isolated
         */
        @Provides
        @IntoSet
        McpRequestLifecycleObserver silent() {
            return nullObserver();
        }

        /**
         * Contributes the first healthy completion listener.
         *
         * @return the recording listener whose callback the row asserts
         */
        @Provides
        @IntoSet
        McpRequestCompletedListener firstCompleted() {
            return firstListener;
        }

        /**
         * Contributes the second healthy completion listener.
         *
         * @return the recording listener whose callback the row asserts
         */
        @Provides
        @IntoSet
        McpRequestCompletedListener secondCompleted() {
            return secondListener;
        }

        /**
         * Contributes a completion listener that throws.
         *
         * @return the failing listener whose failure must be isolated
         */
        @Provides
        @IntoSet
        McpRequestCompletedListener failingCompleted() {
            return throwingListener();
        }
    }

    /** Contributes two recording completion listeners and no observer at all. */
    @Module
    static final class ListenerOnlyContributions {
        private final RecordingListener firstListener;
        private final RecordingListener secondListener;

        ListenerOnlyContributions(RecordingListener firstListener, RecordingListener secondListener) {
            this.firstListener = firstListener;
            this.secondListener = secondListener;
        }

        /**
         * Contributes the first healthy completion listener.
         *
         * @return the recording listener whose callback the row asserts
         */
        @Provides
        @IntoSet
        McpRequestCompletedListener firstCompleted() {
            return firstListener;
        }

        /**
         * Contributes the second healthy completion listener.
         *
         * @return the recording listener whose callback the row asserts
         */
        @Provides
        @IntoSet
        McpRequestCompletedListener secondCompleted() {
            return secondListener;
        }
    }

    // --- Recording contributions ---

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
