// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client;

import static dev.vertique.http.client.HttpClientItSupport.await;
import static dev.vertique.http.client.HttpClientItSupport.awaitCondition;
import static dev.vertique.http.client.HttpClientItSupport.awaitFailure;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.config.parser.ConfigParsingModule;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.lifecycle.ApplicationShutdownStep;
import dev.vertique.core.lifecycle.ComposeValidator;
import dev.vertique.core.lifecycle.LifecycleOrdered;
import dev.vertique.http.client.HttpClientItSupport.RecordingServer;
import dev.vertique.http.client.exception.HttpBodyLimitException;
import dev.vertique.http.client.exception.HttpClientClosedException;
import dev.vertique.http.client.exception.HttpClientConfigurationException;
import dev.vertique.http.client.exception.HttpEgressDeniedException;
import dev.vertique.http.client.interceptor.HttpClientAttemptCompletion;
import dev.vertique.http.client.interceptor.HttpClientInterceptor;
import dev.vertique.http.client.interceptor.HttpClientRequestContext;
import dev.vertique.http.client.interceptor.HttpClientResponseHead;
import dev.vertique.resilience.dagger.ResiliencePoliciesModule;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.PemTrustOptions;
import io.vertx.core.net.ProxyOptions;
import io.vertx.core.net.SelfSignedCertificate;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClientOptions;
import jakarta.annotation.Nullable;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Integration tests of named client configuration, the factory, the Dagger graph and the client
 * lifecycle, against real Vert.x servers on {@code 127.0.0.1} and dynamic ports.
 *
 * <p>The application graph is a Dagger component that includes {@link HttpClientModule}, the
 * configuration parser and the {@code resilience.policies} registry module, and takes its
 * configuration from a {@code @VertxConfig JsonObject}.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class HttpClientFactoryIT {

    private static final long SETTLE_MS = 300;

    private static HttpClientItSupport support;

    @BeforeAll
    static void startVertx() {
        support = HttpClientItSupport.start();
    }

    @AfterEach
    void closeResources() {
        support.closeResources();
        support.assertNoBlockedThreadWarning();
    }

    @AfterAll
    static void stopVertx() {
        support.stop();
    }

    // --- fixtures: the application graph ---

    /** The application graph of these tests. */
    @Singleton
    @Component(
            modules = {
                HttpClientModule.class,
                ConfigParsingModule.class,
                ResiliencePoliciesModule.class,
                HttpClientFactoryIT.GraphModule.class
            })
    interface AppComponent {
        HttpClientFactory factory();

        Set<HttpClientInterceptor> globalInterceptors();

        Set<ComposeValidator> composeValidators();

        Set<ApplicationShutdownStep> shutdownSteps();
    }

    /** Supplies the Vert.x instance, the application configuration and one global interceptor. */
    @Module
    static final class GraphModule {
        private final Vertx vertx;
        private final JsonObject config;
        private final HttpClientInterceptor global;

        GraphModule(Vertx vertx, JsonObject config, HttpClientInterceptor global) {
            this.vertx = vertx;
            this.config = config;
            this.global = global;
        }

        @Provides
        Vertx vertx() {
            return vertx;
        }

        @Provides
        @Singleton
        @VertxConfig
        JsonObject vertxConfig() {
            return config;
        }

        @Provides
        @IntoSet
        HttpClientInterceptor globalInterceptor() {
            return global;
        }
    }

    /** Counts the exchanges it is told about. */
    static final class CountingInterceptor implements HttpClientInterceptor {
        final String key;
        final AtomicInteger completed = new AtomicInteger();
        final List<HttpClientAttemptCompletion> completions = new java.util.concurrent.CopyOnWriteArrayList<>();

        CountingInterceptor(String key) {
            this.key = key;
        }

        @Override
        public String orderKey() {
            return key;
        }

        @Override
        public void onAttemptCompleted(
                HttpClientRequestContext request,
                @Nullable HttpClientResponseHead response,
                HttpClientAttemptCompletion completion) {
            completed.incrementAndGet();
            completions.add(completion);
        }
    }

    private AppComponent component(JsonObject config, HttpClientInterceptor global) {
        AppComponent component = DaggerHttpClientFactoryIT_AppComponent.builder()
                .graphModule(new GraphModule(support.vertx, config, global))
                .build();
        return component;
    }

    /** Registers the application stop for cleanup, so a test that fails early leaves nothing open. */
    private AppComponent tracked(AppComponent component) {
        support.track(() -> stop(component));
        return component;
    }

    /** Runs every shutdown step in lifecycle order, as the application stop does. */
    private static Future<Void> stop(AppComponent component) {
        List<ApplicationShutdownStep> steps = new ArrayList<>(component.shutdownSteps());
        steps.sort(LifecycleOrdered.comparator());
        Future<Void> chain = Future.succeededFuture();
        for (ApplicationShutdownStep step : steps) {
            chain = chain.compose(v -> step.stop());
        }
        return chain;
    }

    private static JsonObject section(JsonObject clients) {
        return new JsonObject().put("httpClient", clients);
    }

    private static void redirect(HttpServerRequest request, String path) {
        request.response()
                .setStatusCode(302)
                .putHeader(
                        "Location", "http://127.0.0.1:" + request.localAddress().port() + path)
                .end();
    }

    /** Waits for a future to finish either way. */
    private static void settle(Future<?> future) {
        await(future.<Object>map(v -> null).otherwiseEmpty());
    }

    private RecordingServer apiServer() {
        return support.server((request, body) -> {
            switch (request.path()) {
                case "/api/ping" -> request.response().end("pong");
                case "/api/big" -> request.response().end("x".repeat(2000));
                case "/api/stall" -> {
                    // never answers
                }
                case "/api/r1" -> redirect(request, "/api/r2");
                case "/api/r2" -> redirect(request, "/api/r3");
                case "/api/r3" -> redirect(request, "/api/r4");
                case "/api/r4" -> request.response().end("end");
                default -> request.response().setStatusCode(404).end();
            }
        });
    }

    private static ClientSettings settings(VertiqueHttpClient client) {
        return ((DefaultVertiqueHttpClient) client).settings();
    }

    // --- TP-001 / AC-049 ---

    /** AC-049. */
    @Test
    @DisplayName("AC-049: a named client applies its configuration")
    void namedClientAppliesItsConfiguration() {
        // Given the configuration of httpClient.search and a global interceptor in the Dagger set
        RecordingServer server = apiServer();
        CountingInterceptor global = new CountingInterceptor("global");
        JsonObject search = new JsonObject()
                .put("baseUrl", server.origin() + "/api")
                .put("readTimeoutMs", 250)
                .put("maxResponseBytes", 1024)
                .put("webClient", new JsonObject().put("followRedirects", true).put("maxRedirects", 2))
                .put(
                        "egress",
                        new JsonObject()
                                .put("allowHosts", new JsonArray().add("127.0.0.1"))
                                .put("allowPlainHttp", true))
                .put("defaultHeaders", new JsonObject().put("X-App", "search"));
        AppComponent app = tracked(component(section(new JsonObject().put("search", search)), global));
        HttpClientFactory factory = app.factory();

        // When the application obtains client("search") twice and runs GET /ping
        VertiqueHttpClient first = factory.client("search");
        VertiqueHttpClient second = factory.client("search");
        HttpResponse<Buffer> ping = await(first.execute(web -> web.get("/ping").send()));

        // Then both calls return the same instance and the request reached /api/ping carrying X-App: search
        assertThat(second).isSameAs(first);
        assertThat(ping.bodyAsString()).isEqualTo("pong");
        assertThat(server.requestsTo("/api/ping")).hasSize(1);
        assertThat(server.requestsTo("/api/ping").get(0).headers().get("X-App")).isEqualTo("search");

        // And the global interceptor is notified once
        assertThat(awaitCondition(() -> global.completed.get() >= 1, HttpClientItSupport.WAIT_MS))
                .isTrue();
        sleep(SETTLE_MS);
        assertThat(global.completed.get()).isEqualTo(1);

        // And a response over 1024 bytes fails with HttpBodyLimitException
        assertThat(awaitFailure(first.execute(web -> web.get("/big").send())))
                .isInstanceOf(HttpBodyLimitException.class);

        // And a stalled response fails with a TimeoutException after about 250 ms
        long started = System.nanoTime();
        Throwable timeout = awaitFailure(first.execute(web -> web.get("/stall").send()));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertThat(timeout).isInstanceOf(TimeoutException.class);
        assertThat(elapsedMs).isBetween(200L, 5_000L);

        // And a 3-hop redirect chain stops after 2 hops
        settle(first.execute(web -> web.get("/r1").send()));
        assertThat(server.requestsTo("/api/r1")).hasSize(1);
        assertThat(server.requestsTo("/api/r2")).hasSize(1);
        assertThat(server.requestsTo("/api/r3")).hasSize(1);
        assertThat(server.requestsTo("/api/r4")).isEmpty();

        // And the egress allow-list applies: a host outside it is refused before any connection
        int connections = server.openedConnections.get();
        Throwable denied = awaitFailure(first.execute(web ->
                web.getAbs("http://localhost:" + server.port() + "/api/ping").send()));
        assertThat(denied).isInstanceOf(HttpEgressDeniedException.class);
        assertThat(server.openedConnections.get()).isEqualTo(connections);

        // And client("other") without a section has the defaults: 30000 ms, 10485760 bytes, no redirects followed
        VertiqueHttpClient other = factory.client("other");
        assertThat(other).isNotSameAs(first);
        assertThat(settings(other).readTimeoutMs()).isEqualTo(30_000);
        assertThat(settings(other).maxResponseBytes()).isEqualTo(10_485_760);
        HttpResponse<Buffer> notFollowed = await(
                other.execute(web -> web.getAbs(server.origin() + "/api/r1").send()));
        assertThat(notFollowed.statusCode()).isEqualTo(302);
        assertThat(server.requestsTo("/api/r2")).as("no redirect followed").hasSize(1);
    }

    /** FR-020: the webClient bag is merged over whichever base options apply. */
    @Test
    @DisplayName("the webClient bag is merged over the builder's base options")
    void webClientBagMergesOverTheBuilderBase() {
        // Given a bag with maxRedirects 1 and no followRedirects, and a redirect chain
        RecordingServer server = apiServer();
        JsonObject bagOnly = new JsonObject()
                .put("baseUrl", server.origin() + "/api")
                .put("webClient", new JsonObject().put("maxRedirects", 1));
        AppComponent app =
                tracked(component(section(new JsonObject().put("merge", bagOnly)), new CountingInterceptor("g")));

        // When the client is built on the framework's base options
        VertiqueHttpClientBuilder onFrameworkBase = app.factory().builder("merge");
        support.track(onFrameworkBase::close);
        VertiqueHttpClient noRedirects = onFrameworkBase.build();
        HttpResponse<Buffer> notFollowed =
                await(noRedirects.execute(web -> web.get("/r1").send()));

        // Then followRedirects stays false, as the base says
        assertThat(notFollowed.statusCode()).isEqualTo(302);
        assertThat(server.requestsTo("/api/r2")).isEmpty();

        // When the Java builder replaced the base with followRedirects true
        VertiqueHttpClientBuilder onReplacedBase = app.factory().builder("merge");
        support.track(onReplacedBase::close);
        VertiqueHttpClient oneHop = onReplacedBase
                .webClientOptions(new WebClientOptions().setFollowRedirects(true))
                .build();
        settle(oneHop.execute(web -> web.get("/r1").send()));

        // Then the bag's maxRedirects 1 applies over that base and followRedirects stays true
        assertThat(server.requestsTo("/api/r2")).hasSize(1);
        assertThat(server.requestsTo("/api/r3")).isEmpty();
    }

    /** FR-020: a non-empty bag does not drop the base's trust and proxy options. */
    @Test
    @DisplayName("a non-empty webClient bag keeps the builder base's trust and proxy options")
    void webClientBagKeepsTheBuilderBaseTrustAndProxyOptions() throws Exception {
        // Given a TLS server whose self-signed certificate only the Java base trusts
        SelfSignedCertificate certificate = SelfSignedCertificate.create();
        support.track(certificate::delete);
        RecordingServer tls = RecordingServer.start(
                support.vertx,
                (request, body) -> request.response().end("secure"),
                new HttpServerOptions().setSsl(true).setKeyCertOptions(certificate.keyCertOptions()));
        support.track(tls::close);
        Buffer pem =
                Buffer.buffer(java.nio.file.Files.readAllBytes(java.nio.file.Path.of(certificate.certificatePath())));

        // And a proxy that records what it is asked for
        RecordingServer proxy =
                support.server((request, body) -> request.response().end("proxied"));

        // And configuration with a non-empty bag that sets neither trust nor proxy
        JsonObject config = section(new JsonObject()
                .put(
                        "trusted",
                        new JsonObject()
                                .put("baseUrl", tls.origin())
                                .put("webClient", new JsonObject().put("maxRedirects", 1)))
                .put(
                        "proxied",
                        new JsonObject()
                                .put("baseUrl", "http://zz-target.invalid:8081")
                                .put("webClient", new JsonObject().put("maxRedirects", 1))));
        AppComponent app = tracked(component(config, new CountingInterceptor("g")));

        // When a client is built on a Java base with trust options (host verification off for 127.0.0.1)
        VertiqueHttpClientBuilder trustedBuilder = app.factory().builder("trusted");
        support.track(trustedBuilder::close);
        VertiqueHttpClient trusted = trustedBuilder
                .webClientOptions(new WebClientOptions()
                        .setVerifyHost(false)
                        .setTrustOptions(new PemTrustOptions().addCertValue(pem)))
                .build();

        // Then the TLS call succeeds, so the trust options survived the merge
        assertThat(await(trusted.execute(web -> web.get("/hello").send())).bodyAsString())
                .isEqualTo("secure");

        // When a client is built on a Java base with proxy options
        VertiqueHttpClientBuilder proxiedBuilder = app.factory().builder("proxied");
        support.track(proxiedBuilder::close);
        VertiqueHttpClient proxied = proxiedBuilder
                .webClientOptions(new WebClientOptions()
                        .setProxyOptions(new ProxyOptions()
                                .setHost("127.0.0.1")
                                .setPort(proxy.port())
                                .setUsername("u")
                                .setPassword("p")))
                .build();

        // Then the request went to the proxy with its credentials, so the proxy settings survived the merge
        assertThat(await(proxied.execute(web -> web.get("/via").send())).bodyAsString())
                .isEqualTo("proxied");
        assertThat(proxy.requests).hasSize(1);
        assertThat(proxy.requests.get(0).uri()).contains("zz-target.invalid").contains("/via");
        assertThat(proxy.requests.get(0).headers().get("Proxy-Authorization")).isEqualTo("Basic dTpw");
    }

    // --- TP-004 / AC-062 ---

    /** AC-062. */
    @Test
    @DisplayName("AC-062: close fails in-flight calls once and is idempotent")
    void closeFailsInFlightCallsOnceAndIsIdempotent() {
        // Given a client with defaults, a recording interceptor and a server that never answers GET /never
        RecordingServer server = support.server((request, body) -> {});
        CountingInterceptor recorder = new CountingInterceptor("rec");
        VertiqueHttpClientBuilder builder = VertiqueHttpClientBuilder.create(support.vertx)
                .baseUrl(server.origin())
                .register(recorder);
        support.track(builder::close);
        VertiqueHttpClient client = builder.build();
        support.track(client::close);

        // And 2 calls in flight
        Future<HttpResponse<Buffer>> one =
                client.execute(web -> web.get("/never").send());
        Future<HttpResponse<Buffer>> two =
                client.execute(web -> web.get("/never").send());
        assertThat(awaitCondition(() -> server.requestsTo("/never").size() == 2, HttpClientItSupport.WAIT_MS))
                .isTrue();

        // When the caller calls close() twice
        Future<Void> firstClose = client.close();
        Future<Void> secondClose = client.close();

        // Then both in-flight futures fail with HttpClientClosedException
        assertThat(awaitFailure(one)).isExactlyInstanceOf(HttpClientClosedException.class);
        assertThat(awaitFailure(two)).isExactlyInstanceOf(HttpClientClosedException.class);

        // And the interceptor was notified exactly once per in-flight exchange, 2 times in total
        assertThat(awaitCondition(() -> recorder.completed.get() >= 2, HttpClientItSupport.WAIT_MS))
                .isTrue();
        sleep(SETTLE_MS);
        assertThat(recorder.completed.get()).isEqualTo(2);

        // And both close() calls return the same future
        assertThat(secondClose).isSameAs(firstClose);
        await(firstClose);
        assertThat(client.close()).isSameAs(firstClose);

        // And a call after close() fails with HttpClientClosedException without opening a connection
        int connections = server.openedConnections.get();
        Future<HttpResponse<Buffer>> after =
                client.execute(web -> web.get("/never").send());
        assertThat(awaitFailure(after)).isExactlyInstanceOf(HttpClientClosedException.class);
        sleep(SETTLE_MS);
        assertThat(server.openedConnections.get()).isEqualTo(connections);
        assertThat(server.requestsTo("/never")).hasSize(2);
        assertThat(recorder.completed.get())
                .as("a call refused by a closed client is not notified")
                .isEqualTo(2);
    }

    // --- TP-007 ---

    /** FR-020. */
    @Test
    @DisplayName("the Dagger graph provides the factory, the global interceptor set and the startup validator")
    void daggerGraphProvidesTheFactoryWithGlobalInterceptorsAndStartupValidator() {
        // Given a graph with a global interceptor, a configured client and the resilience.policies tier "fast"
        AtomicInteger seen = new AtomicInteger();
        RecordingServer server = support.server((request, body) -> {
            if (seen.incrementAndGet() % 2 == 1) {
                request.response().setStatusCode(503).end();
            } else {
                request.response().end("ok");
            }
        });
        CountingInterceptor global = new CountingInterceptor("global");
        JsonObject config = section(new JsonObject()
                        .put("plain", new JsonObject().put("baseUrl", server.origin()))
                        .put(
                                "dflt",
                                new JsonObject().put("baseUrl", server.origin()).put("resiliencePolicy", "fast")))
                .put(
                        "resilience",
                        new JsonObject()
                                .put(
                                        "policies",
                                        new JsonObject()
                                                .put(
                                                        "fast",
                                                        new JsonObject()
                                                                .put(
                                                                        "retry",
                                                                        new JsonObject()
                                                                                .put("maxRetries", 1)
                                                                                .put("delayMs", 10)))));
        AppComponent app = tracked(component(config, global));

        // Then the graph provides the factory as a singleton and the global interceptor set
        HttpClientFactory factory = app.factory();
        assertThat(factory).isNotNull().isSameAs(app.factory());
        assertThat(app.globalInterceptors()).containsExactly(global);

        // And the startup validators materialize without failure for a valid configuration
        assertThat(app.composeValidators()).isNotEmpty();

        // And the application stop is contributed as a shutdown step
        assertThat(app.shutdownSteps()).isNotEmpty();

        // And a client the factory builds is seeded with the global interceptor
        VertiqueHttpClient plain = factory.client("plain");
        await(plain.execute(web -> web.get("/hello").send().map(HttpResponse::statusCode)));
        assertThat(awaitCondition(() -> global.completed.get() >= 1, HttpClientItSupport.WAIT_MS))
                .isTrue();
        int afterPlain = global.completed.get();

        // And so is a builder from the factory
        VertiqueHttpClientBuilder builder = factory.builder("plain");
        support.track(builder::close);
        VertiqueHttpClient built = builder.build();
        await(built.execute(web -> web.get("/hello").send()));
        assertThat(awaitCondition(() -> global.completed.get() > afterPlain, HttpClientItSupport.WAIT_MS))
                .isTrue();

        // And the factory supplies the resilience.policies registry: a call naming the tier "fast" retries a 503
        int before = server.requests.size();
        String body = await(plain.execute(CallOptions.defaults().resiliencePolicy("fast"), web -> web.get("/tiered")
                .send()
                .expecting(HttpExpectations.success())
                .map(HttpResponse::bodyAsString)));
        assertThat(body).isEqualTo("ok");
        assertThat(server.requests.size() - before).isEqualTo(2);

        // And so does the client's own resiliencePolicy key, with no policy named on the call
        VertiqueHttpClient tiered = factory.client("dflt");
        int beforeDefault = server.requests.size();
        String viaDefault = await(tiered.execute(web ->
                web.get("/tiered").send().expecting(HttpExpectations.success()).map(HttpResponse::bodyAsString)));
        assertThat(viaDefault).isEqualTo("ok");
        assertThat(server.requests.size() - beforeDefault).isEqualTo(2);

        // And a call naming a tier that is not defined fails
        assertThat(awaitFailure(plain.execute(CallOptions.defaults().resiliencePolicy("nope"), web -> web.get("/x")
                        .send())))
                .isNotNull();
    }

    /** AC-050: the startup validator rejects what only the graph can know. */
    @Test
    @DisplayName("startup fails for a resiliencePolicy that names an unknown tier, without the tier name")
    void startupFailsForAnUnknownResilienceTier() {
        // Given a client whose resiliencePolicy names a tier that resilience.policies does not define
        JsonObject config = section(new JsonObject()
                        .put(
                                "a",
                                new JsonObject()
                                        .put("baseUrl", "http://127.0.0.1:1")
                                        .put("resiliencePolicy", "zz-nope-tier")))
                .put(
                        "resilience",
                        new JsonObject()
                                .put(
                                        "policies",
                                        new JsonObject()
                                                .put(
                                                        "fast",
                                                        new JsonObject()
                                                                .put(
                                                                        "retry",
                                                                        new JsonObject()
                                                                                .put("maxRetries", 1)
                                                                                .put("delayMs", 10)))));
        AppComponent app = tracked(component(config, new CountingInterceptor("g")));

        // When the startup validators are materialized
        // Then startup fails with a constant message naming the key path and not the tier
        assertThatThrownBy(app::composeValidators)
                .isInstanceOf(HttpClientConfigurationException.class)
                .hasMessageContaining("httpClient.a.resiliencePolicy")
                .satisfies(e -> assertThat(e.toString()).doesNotContain("zz-nope-tier"));
    }

    // --- TP-008 ---

    /** FR-024. */
    @Test
    @DisplayName("application shutdown closes every factory client")
    void applicationShutdownClosesEveryFactoryClient() {
        // Given two factory clients with a call in flight each, and a third that is idle
        RecordingServer server = support.server((request, body) -> {});
        JsonObject config = section(new JsonObject()
                .put("a", new JsonObject().put("baseUrl", server.origin()))
                .put("b", new JsonObject().put("baseUrl", server.origin()))
                .put("c", new JsonObject().put("baseUrl", server.origin())));
        AppComponent app = tracked(component(config, new CountingInterceptor("g")));
        HttpClientFactory factory = app.factory();
        VertiqueHttpClient a = factory.client("a");
        VertiqueHttpClient b = factory.client("b");
        VertiqueHttpClient c = factory.client("c");
        Future<HttpResponse<Buffer>> inA = a.execute(web -> web.get("/never").send());
        Future<HttpResponse<Buffer>> inB = b.execute(web -> web.get("/never").send());
        assertThat(awaitCondition(() -> server.requestsTo("/never").size() == 2, HttpClientItSupport.WAIT_MS))
                .isTrue();

        // When the application stops, running the shutdown steps in lifecycle order
        await(stop(app));

        // Then both in-flight calls fail with HttpClientClosedException
        assertThat(awaitFailure(inA)).isExactlyInstanceOf(HttpClientClosedException.class);
        assertThat(awaitFailure(inB)).isExactlyInstanceOf(HttpClientClosedException.class);

        // And every factory client, including the idle one, refuses new calls
        for (VertiqueHttpClient client : List.of(a, b, c)) {
            assertThat(awaitFailure(client.execute(web -> web.get("/after").send())))
                    .isExactlyInstanceOf(HttpClientClosedException.class);
        }
        assertThat(server.requestsTo("/after")).isEmpty();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
