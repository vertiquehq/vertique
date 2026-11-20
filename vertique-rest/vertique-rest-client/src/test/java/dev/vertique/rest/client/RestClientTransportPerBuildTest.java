// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.http.client.CallOptions;
import dev.vertique.http.client.HttpClient;
import dev.vertique.http.client.HttpClientBuilder;
import dev.vertique.http.client.exception.HttpClientClosedException;
import dev.vertique.resilience.BackoffStrategy;
import dev.vertique.resilience.Resilience;
import dev.vertique.resilience.ResiliencePolicyRegistry;
import dev.vertique.resilience.adapter.ResilienceAdapterContext;
import dev.vertique.resilience.adapter.ResilienceAdapterSupport;
import dev.vertique.resilience.annotation.Resilient;
import dev.vertique.resilience.annotation.Retry;
import dev.vertique.resilience.exception.ResilienceClosedException;
import dev.vertique.rest.client.config.RestClientConfig;
import dev.vertique.rest.client.exception.RestClientUnavailableException;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.PoolOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClientOptions;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.AdditionalAnswers;
import org.mockito.ArgumentCaptor;

/**
 * The REST builder gives every built proxy its own governed HTTP client, built from the effective
 * options, and releases it with the builder. The test substitutes the package-private transport
 * seam {@code RestClientBuilder.HttpTransportFactory}, which hands the REST builder a
 * {@link HttpClientBuilder} per built proxy; the REST builder applies its settings to that
 * builder and calls {@code build()} once on it.
 *
 * <p>Proves AC-063, AC-064, AC-065, AC-066, AC-067 and the FR-017 settings: the REST client
 * carries no client timer, body bounds, request-header validation, egress policy or credentials.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class RestClientTransportPerBuildTest {

    private static final long AWAIT_SECONDS = 20;

    private static Vertx vertx;

    @BeforeAll
    static void startVertx() {
        vertx = Vertx.vertx();
    }

    @AfterAll
    static void stopVertx() {
        if (vertx != null) {
            join(vertx.close());
        }
    }

    // --- Test client interfaces ---

    @RestClient(name = "per-build", value = "http://localhost:1")
    interface Probe {
        @GET
        @Path("/resource")
        Future<String> resource();
    }

    @RestClient(name = "pool-client", value = "http://localhost:1")
    interface PoolClient {
        @GET
        @Path("/resource")
        Future<String> resource();
    }

    @RestClient(name = "policy-client", value = "http://localhost:1")
    interface NamedPolicyClient {
        @Resilient(policy = "missing")
        @Retry(maxRetries = 1)
        @GET
        Future<String> call();
    }

    @RestClient(name = "header-client")
    interface HeaderClient {
        @GET
        @Path("/echo")
        Future<HttpClientResponse> echo(@HeaderParam("Vertique-Probe") String probe);
    }

    @RestClient(name = "hold-client")
    interface HoldClient {
        @GET
        @Path("/hold")
        Future<String> hold();
    }

    @RestClient(name = "retry-client")
    interface RetryClient {
        @GET
        @Path("/flaky")
        @Retry(maxRetries = 2, backoff = NoDelayBackoff.class)
        Future<HttpClientResponse> flaky();
    }

    /** No delay between retries, so the test stays fast. */
    public static class NoDelayBackoff implements BackoffStrategy {
        /** Creates the strategy. */
        public NoDelayBackoff() {}

        @Override
        public long delay(int retryCount) {
            return 0L;
        }
    }

    // --- Fixtures ---

    /**
     * A governed client that forwards to a real one, counting {@code execute} calls. Its {@code close()}
     * first reports to a hook and forwards the close only once the future the hook returns has settled,
     * so a call the hook starts is not cancelled by a close it precedes.
     */
    private static final class RecordingClient {
        final HttpClient client;
        final AtomicInteger executeWithOptions = new AtomicInteger();
        final AtomicInteger executeWithoutOptions = new AtomicInteger();

        RecordingClient(HttpClient delegate, Supplier<Future<?>> beforeClose) {
            this.client = mock(HttpClient.class, AdditionalAnswers.delegatesTo(delegate));
            doAnswer(invocation -> {
                        executeWithoutOptions.incrementAndGet();
                        return delegate.execute(invocation.getArgument(0));
                    })
                    .when(client)
                    .execute(any(Function.class));
            doAnswer(invocation -> {
                        executeWithOptions.incrementAndGet();
                        return delegate.execute(invocation.getArgument(0), invocation.getArgument(1));
                    })
                    .when(client)
                    .execute(any(CallOptions.class), any());
            doAnswer(invocation -> beforeClose
                            .get()
                            .<Void>transform(ignored -> Future.succeededFuture())
                            .compose(ignored -> delegate.close()))
                    .when(client)
                    .close();
        }
    }

    /**
     * A transport factory. By default its governed builders and clients are mocks: every governed
     * builder is a self-returning mock whose {@code build()} yields a fresh mock client whose
     * {@code close()} records itself in {@link #events} and runs {@link #onClientClose}. The
     * {@link #delegating()} variant wraps a real governed builder and client instead, so a call made
     * through a built proxy runs its pipeline for real.
     */
    private static final class FakeTransport {
        final List<HttpClientBuilder> builders = new CopyOnWriteArrayList<>();
        final List<HttpClient> clients = new CopyOnWriteArrayList<>();
        final List<RecordingClient> recordings = new CopyOnWriteArrayList<>();
        final List<String> events = Collections.synchronizedList(new ArrayList<>());
        volatile Supplier<Future<?>> onClientClose = Future::succeededFuture;
        private final boolean delegating;

        FakeTransport() {
            this(false);
        }

        private FakeTransport(boolean delegating) {
            this.delegating = delegating;
        }

        static FakeTransport delegating() {
            return new FakeTransport(true);
        }

        RecordingClient recorded(int index) {
            return recordings.get(index);
        }

        RestClientBuilder.HttpTransportFactory factory() {
            return delegating ? this::realBuilder : this::mockBuilder;
        }

        private HttpClientBuilder mockBuilder(Vertx ignored) {
            HttpClientBuilder builder = mock(HttpClientBuilder.class, RETURNS_SELF);
            HttpClient client = mock(HttpClient.class);
            String id = "client-" + clients.size();
            when(client.close()).thenAnswer(invocation -> {
                events.add(id);
                onClientClose.get();
                return Future.succeededFuture();
            });
            when(builder.build()).thenReturn(client);
            builders.add(builder);
            clients.add(client);
            return builder;
        }

        private HttpClientBuilder realBuilder(Vertx vertx) {
            HttpClientBuilder builder = spy(HttpClientBuilder.create(vertx));
            doAnswer(invocation -> {
                        HttpClient real = (HttpClient) invocation.callRealMethod();
                        String id = "client-" + clients.size();
                        RecordingClient recording = new RecordingClient(real, () -> {
                            events.add(id);
                            return onClientClose.get();
                        });
                        recordings.add(recording);
                        clients.add(recording.client);
                        return recording.client;
                    })
                    .when(builder)
                    .build();
            builders.add(builder);
            return builder;
        }
    }

    private static RestClientBuilder builderOver(FakeTransport transport) {
        return new RestClientBuilder(vertx, ResiliencePolicyRegistry.empty(), transport.factory());
    }

    private static <T> T join(Future<T> future) {
        try {
            return future.toCompletionStage().toCompletableFuture().get(AWAIT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("future did not complete successfully", e);
        }
    }

    private static Throwable failureOf(Future<?> future) {
        try {
            future.toCompletionStage().toCompletableFuture().get(AWAIT_SECONDS, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            return e.getCause();
        } catch (Exception e) {
            throw new AssertionError("future did not complete", e);
        }
        throw new AssertionError("expected the call to fail");
    }

    /** A future that completes once every given future has completed, whatever their outcome. */
    private static Future<?> settled(List<? extends Future<?>> futures) {
        return Future.join(new ArrayList<>(futures)).transform(ignored -> Future.succeededFuture());
    }

    private static boolean accepts(Resilience runtime) {
        try {
            runtime.pipeline("probe");
            return true;
        } catch (IllegalStateException closed) {
            return false;
        }
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    // --- Tests ---

    @Test
    @DisplayName("Each build creates a fresh governed client from the effective options")
    void eachBuildCreatesAFreshGovernedClientFromTheEffectiveOptions() {
        // AC-063. Given a transport seam that records every governed client it creates
        FakeTransport transport = new FakeTransport();
        RestClientBuilder builder = builderOver(transport)
                .webClientOptions(new WebClientOptions().setConnectTimeout(1234))
                .poolOptions(new PoolOptions().setHttp1MaxSize(7));
        try {
            // When the same interface is built twice
            Probe first = builder.build(Probe.class);
            Probe second = builder.build(Probe.class);

            // Then two distinct governed clients were created, one per proxy
            assertThat(first).isNotSameAs(second);
            assertThat(transport.builders).hasSize(2);
            assertThat(transport.clients).hasSize(2);
            assertThat(transport.clients.get(0)).isNotSameAs(transport.clients.get(1));
            transport.builders.forEach(governed -> verify(governed, times(1)).build());

            // And each governed builder received the effective options
            for (HttpClientBuilder governed : transport.builders) {
                ArgumentCaptor<WebClientOptions> webClient = ArgumentCaptor.forClass(WebClientOptions.class);
                ArgumentCaptor<PoolOptions> pool = ArgumentCaptor.forClass(PoolOptions.class);
                verify(governed).webClientOptions(webClient.capture());
                verify(governed).poolOptions(pool.capture());
                assertThat(webClient.getValue().getConnectTimeout()).isEqualTo(1234);
                assertThat(pool.getValue().getHttp1MaxSize()).isEqualTo(7);
            }
        } finally {
            join(builder.close());
        }
    }

    @Test
    @DisplayName("A failed build closes the governed client, its context and a runtime it created")
    void failedBuildClosesTheGovernedClientItsContextAndACreatedRuntime() {
        // AC-064, first failure: an unknown named policy fails the eager pipeline construction
        FakeTransport unknownPolicy = new FakeTransport();
        ResiliencePolicyRegistry empty = name -> {
            throw new ConfigurationException("resilience.policies." + name + " is not defined");
        };
        RestClientBuilder owning = new RestClientBuilder(vertx, empty, unknownPolicy.factory());
        try {
            // When the build fails
            assertThatThrownBy(() -> owning.build(NamedPolicyClient.class))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessage("resilience.policies.missing is not defined");

            // Then the governed client it had created is closed, and so is the runtime it created
            assertThat(unknownPolicy.clients).hasSize(1);
            verify(unknownPolicy.clients.get(0), times(1)).close();
            Resilience created = owning.ownedResilience();
            assertThat(created).as("the failed build created a runtime").isNotNull();
            assertThat(accepts(created)).as("the created runtime is closed").isFalse();
        } finally {
            join(owning.close());
        }

        // AC-064, second failure: the generated proxy cannot be instantiated after the client's
        // adapter context exists, on a supplied runtime that must stay open
        FakeTransport brokenProxy = new FakeTransport();
        List<String> contextEvents = Collections.synchronizedList(new ArrayList<>());
        Resilience supplied = contextRecordingRuntime(contextEvents);
        RestClientBuilder supplying = builderOver(brokenProxy).suppliedResilience(supplied);
        try {
            assertThatThrownBy(() -> supplying.build(BrokenSelectionClient.class))
                    .isInstanceOf(dev.vertique.rest.client.exception.RestClientConfigurationException.class);

            assertThat(brokenProxy.clients).hasSize(1);
            verify(brokenProxy.clients.get(0), times(1)).close();
            assertThat(contextEvents)
                    .as("the adapter context created for the failed build is closed")
                    .containsExactly("context-created", "context-closed");
            assertThat(accepts(supplied)).as("a supplied runtime stays open").isTrue();
        } finally {
            join(supplying.close());
            join(supplied.close());
        }
    }

    @Test
    @DisplayName("Builder close closes the contexts and governed clients, then the owned runtime, once")
    void builderCloseClosesContextsAndGovernedClientsThenTheOwnedRuntimeOnce() {
        // AC-065. Given a builder that owns its runtime and built two clients over real governed
        // clients; every time a governed client closes, a call on each proxy and the owned runtime
        // are probed
        FakeTransport transport = FakeTransport.delegating();
        RestClientBuilder builder = builderOver(transport);
        Probe first = builder.build(Probe.class);
        Probe second = builder.build(Probe.class);
        Resilience owned = builder.ownedResilience();
        assertThat(owned).as("the builder owns a runtime").isNotNull();
        List<Future<String>> probes = Collections.synchronizedList(new ArrayList<>());
        List<Boolean> runtimeOpenWhenAClientClosed = Collections.synchronizedList(new ArrayList<>());
        AtomicReference<Future<?>> gate = new AtomicReference<>();
        transport.onClientClose = () -> {
            runtimeOpenWhenAClientClosed.add(accepts(owned));
            synchronized (probes) {
                if (gate.get() == null) {
                    probes.add(first.resource());
                    probes.add(second.resource());
                    gate.set(settled(probes));
                }
                return gate.get();
            }
        };

        // When close() is called twice
        Future<Void> firstClose = builder.close();
        Future<Void> secondClose = builder.close();
        join(firstClose);

        // Then both calls return the same future and each governed client closed exactly once
        assertThat(secondClose).isSameAs(firstClose);
        assertThat(transport.events).containsExactlyInAnyOrder("client-0", "client-1");

        // And both adapter contexts were already closed when the first client began to close: a call
        // made then on either proxy is refused as unavailable by its closed pipeline
        assertThat(probes).hasSize(2);
        for (Future<String> probe : probes) {
            Throwable failure = failureOf(probe);
            assertThat(failure).isInstanceOf(RestClientUnavailableException.class);
            assertThat(rootCause(failure)).isInstanceOf(ResilienceClosedException.class);
        }

        // And the owned runtime was still open while the clients closed, and is closed afterwards
        assertThat(runtimeOpenWhenAClientClosed).containsOnly(true);
        assertThat(accepts(owned))
                .as("the owned runtime is closed after the clients")
                .isFalse();
    }

    @Test
    @DisplayName("Close leaves a supplied runtime open")
    void closeLeavesASuppliedRuntimeOpen() {
        // AC-066, first builder: created over a supplied runtime
        FakeTransport transport = FakeTransport.delegating();
        Resilience supplied = Resilience.create(vertx);
        RestClientBuilder builder = builderOver(transport).suppliedResilience(supplied);
        try {
            Probe proxy = builder.build(Probe.class);
            List<Future<String>> probes = Collections.synchronizedList(new ArrayList<>());
            transport.onClientClose = () -> {
                probes.add(proxy.resource());
                return settled(probes);
            };

            // When close() completes
            join(builder.close());

            // Then the governed client is closed, the adapter context was closed before it, and the
            // runtime still accepts new pipelines
            assertThat(transport.events).containsExactly("client-0");
            assertThat(probes).hasSize(1);
            assertThat(failureOf(probes.get(0))).isInstanceOf(RestClientUnavailableException.class);
            assertThat(accepts(supplied)).isTrue();
            assertThat(builder.ownedResilience()).isNull();
        } finally {
            join(supplied.close());
        }

        // AC-066, second builder: created by the factory the application injects
        Resilience suppliedToFactory = Resilience.create(vertx);
        RestClientFactory factory = new RestClientFactory(
                vertx,
                Set.of(),
                Set.of(),
                Map.of(),
                null,
                BeanParamAccessorRegistry.shared(),
                null,
                null,
                null,
                null,
                suppliedToFactory);
        RestClientBuilder fromFactory = factory.builder();
        try {
            fromFactory.build(Probe.class);
            join(fromFactory.close());

            assertThat(accepts(suppliedToFactory)).isTrue();
        } finally {
            join(suppliedToFactory.close());
        }
    }

    @Test
    @DisplayName("Configured pool values reach the governed client")
    void configuredPoolValuesReachTheGovernedClient() {
        // AC-067. Given restClient.pool-client.pool values in the configuration
        JsonObject root = new JsonObject()
                .put(
                        "restClient",
                        new JsonObject()
                                .put(
                                        "pool-client",
                                        new JsonObject()
                                                .put(
                                                        "pool",
                                                        new JsonObject()
                                                                .put("http1MaxSize", 3)
                                                                .put("maxWaitQueueSize", 10))));
        Map<String, RestClientConfig> index =
                RestClientConfig.indexFromConfig(root, new DefaultConfigParser(DefaultConfigMapper.lenient()));
        FakeTransport transport = new FakeTransport();
        RestClientBuilder builder = builderOver(transport).config(index.get("pool-client"));
        try {
            // When the client is built
            builder.build(PoolClient.class);

            // Then the governed client was created with those pool values
            assertThat(transport.builders).hasSize(1);
            ArgumentCaptor<PoolOptions> pool = ArgumentCaptor.forClass(PoolOptions.class);
            verify(transport.builders.get(0)).poolOptions(pool.capture());
            assertThat(pool.getValue().getHttp1MaxSize()).isEqualTo(3);
            assertThat(pool.getValue().getMaxWaitQueueSize()).isEqualTo(10);
        } finally {
            join(builder.close());
        }
    }

    @Test
    @DisplayName("REST-built clients carry no client timer, body bounds, header validation, egress or credentials")
    void restBuiltClientsCarryNoClientTimerBoundsHeaderValidationEgressOrCredentials() throws Exception {
        // FR-017. Given a builder that records the settings the REST builder applies
        FakeTransport transport = new FakeTransport();
        RestClientBuilder recording = builderOver(transport);
        try {
            // When a proxy is built
            recording.build(Probe.class);

            // Then the client timer and both body bounds are switched off
            HttpClientBuilder governed = transport.builders.get(0);
            verify(governed).readTimeout(eq(0L), any(TimeUnit.class));
            verify(governed).maxResponseBytes(0L);
            verify(governed).maxRequestBytes(0L);

            // And no egress policy, credentials, interceptors, default tier, default headers or
            // external client configuration were given to it
            verify(governed, never()).egress(any());
            verify(governed, never()).credentials(any());
            verify(governed, never()).allowCredentialsOverPlainHttp(anyBoolean());
            verify(governed, never()).register(any());
            verify(governed, never()).defaultHeader(any(), any());
            verify(governed, never()).config(any());
        } finally {
            join(recording.close());
        }

        // And request-header validation is off: a header under the governed client's reserved
        // prefix reaches the server through a real governed client
        HttpServer server = vertx.createHttpServer();
        server.requestHandler(request ->
                request.response().setStatusCode(200).end(String.valueOf(request.getHeader("Vertique-Probe"))));
        join(server.listen(0, "127.0.0.1"));
        RestClientBuilder real = new RestClientBuilder(
                        vertx, ResiliencePolicyRegistry.empty(), HttpClientBuilder::create)
                .baseUrl("http://127.0.0.1:" + server.actualPort());
        try {
            HeaderClient client = real.build(HeaderClient.class);
            assertThat(join(client.echo("kept")).bodyAsString()).isEqualTo("kept");
        } finally {
            join(real.close());
            join(server.close());
        }
    }

    @Test
    @DisplayName("The dispatcher passes its prebuilt pipeline to execute once per call")
    void dispatcherPassesItsPrebuiltPipelineToExecute() {
        // FR-017. Given a real governed client observed through the transport seam and a server that
        // fails twice with 503 before answering
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = vertx.createHttpServer();
        server.requestHandler(request -> {
            if (requests.incrementAndGet() <= 2) {
                request.response().setStatusCode(503).end();
            } else {
                request.response().setStatusCode(200).end("recovered");
            }
        });
        join(server.listen(0, "127.0.0.1"));
        FakeTransport transport = FakeTransport.delegating();
        RestClientBuilder builder = builderOver(transport).baseUrl("http://127.0.0.1:" + server.actualPort());
        try {
            RetryClient client = builder.build(RetryClient.class);

            // When a call with @Retry(maxRetries = 2) is made
            HttpClientResponse result = join(client.flaky());

            // Then the method's own pipeline retried inside one execute: three requests reached the
            // server, and the governed client's execute ran once with call options
            assertThat(result.bodyAsString()).isEqualTo("recovered");
            assertThat(requests.get()).isEqualTo(3);
            assertThat(transport.clients).hasSize(1);
            assertThat(transport.recorded(0).executeWithOptions.get()).isEqualTo(1);
            assertThat(transport.recorded(0).executeWithoutOptions.get()).isZero();
        } finally {
            join(builder.close());
            join(server.close());
        }
    }

    @Test
    @DisplayName("A call in flight when the builder closes keeps the recorded failure identity")
    void inFlightCallAtBuilderCloseKeepsTheRecordedIdentity() throws Exception {
        // AC-065. Repeated to cover the race between the context close and the client close
        for (int iteration = 0; iteration < 20; iteration++) {
            // Given a server that holds the response open and notes when its connection closes
            CountDownLatch requestSeen = new CountDownLatch(1);
            CountDownLatch connectionClosed = new CountDownLatch(1);
            HttpServer server = vertx.createHttpServer();
            server.requestHandler(request -> {
                request.connection().closeHandler(ignored -> connectionClosed.countDown());
                requestSeen.countDown();
            });
            join(server.listen(0, "127.0.0.1"));
            RestClientBuilder builder =
                    RestClientBuilder.create(vertx).baseUrl("http://127.0.0.1:" + server.actualPort());
            try {
                HoldClient client = builder.build(HoldClient.class);
                Future<String> call = client.hold();
                assertThat(requestSeen.await(AWAIT_SECONDS, TimeUnit.SECONDS))
                        .as("iteration %d: the server has the request", iteration)
                        .isTrue();

                // When the builder is closed from a plain thread while the call is in flight
                join(builder.close());

                // Then the call fails as unavailable with the closed runtime as the root cause, and
                // the governed client's closed failure appears nowhere in the cause chain
                Throwable failure = failureOf(call);
                assertThat(failure)
                        .as("iteration %d", iteration)
                        .isExactlyInstanceOf(RestClientUnavailableException.class);
                assertThat(rootCause(failure)).isInstanceOf(ResilienceClosedException.class);
                for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                    assertThat(cause).isNotInstanceOf(HttpClientClosedException.class);
                    if (cause.getCause() == cause) {
                        break;
                    }
                }

                // And the server sees the connection closed
                assertThat(connectionClosed.await(AWAIT_SECONDS, TimeUnit.SECONDS))
                        .as("iteration %d: the server sees the connection close", iteration)
                        .isTrue();
            } finally {
                join(builder.close());
                join(server.close());
            }
        }
    }

    @Test
    @DisplayName("A closed failure from the governed client after builder close keeps the recorded identity")
    void aClosedFailureFromTheGovernedClientAfterBuilderCloseKeepsTheRecordedIdentity() {
        // AC-065. Given a mocked governed client whose execute starts the builder's close and then
        // fails with the client's own closed failure, as when its cancel wins the race with the close
        FakeTransport transport = new FakeTransport();
        RestClientBuilder builder = builderOver(transport);
        Probe proxy = builder.build(Probe.class);
        HttpClient client = transport.clients.get(0);
        List<Future<Void>> closes = Collections.synchronizedList(new ArrayList<>());
        when(client.execute(any(CallOptions.class), any())).thenAnswer(invocation -> {
            closes.add(builder.close());
            return Future.failedFuture(new HttpClientClosedException());
        });

        // When a call is made while the builder is still open
        Future<String> call = proxy.resource();

        // Then the call reached execute, and fails as unavailable with the closed runtime as root
        // cause, with the governed client's closed failure nowhere in the cause chain
        Throwable failure = failureOf(call);
        verify(client, times(1)).execute(any(CallOptions.class), any());
        assertThat(closes).hasSize(1);
        join(closes.get(0));
        assertThat(failure).isExactlyInstanceOf(RestClientUnavailableException.class);
        assertThat(rootCause(failure)).isInstanceOf(ResilienceClosedException.class);
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            assertThat(cause).isNotInstanceOf(HttpClientClosedException.class);
            if (cause.getCause() == cause) {
                break;
            }
        }
    }

    // --- Context-recording runtime ---

    /** A supplied runtime whose adapter contexts report their creation and closing to {@code events}. */
    private static Resilience contextRecordingRuntime(List<String> events) {
        Resilience runtime = spy(Resilience.create(vertx));
        ResilienceAdapterSupport support = spy(runtime.adapterSupport());
        doReturn(support).when(runtime).adapterSupport();
        doAnswer(invocation -> {
                    ResilienceAdapterContext context = spy((ResilienceAdapterContext) invocation.callRealMethod());
                    events.add("context-created");
                    doAnswer(close -> {
                                events.add("context-closed");
                                return close.callRealMethod();
                            })
                            .when(context)
                            .close();
                    return context;
                })
                .when(support)
                .newContext();
        return runtime;
    }
}
