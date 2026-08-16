// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.test;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.core.exception.NotFoundException;
import dev.vertique.core.extension.ExtensionPhase;
import dev.vertique.rest.core.interceptor.RequestInterceptor;
import dev.vertique.rest.core.middleware.Middleware;
import dev.vertique.rest.core.middleware.MiddlewareScope;
import dev.vertique.rest.core.middleware.RequestContextLifecycle;
import dev.vertique.rest.core.response.BufferedBody;
import dev.vertique.rest.core.response.ResponseBodyEncoder;
import dev.vertique.rest.core.response.SerializedBody;
import io.swagger.v3.oas.annotations.Operation;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
import io.vertx.core.internal.ContextInternal;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * End-to-end proof that {@link RestTestMounts} turns a fixture-built {@link RestTestMount} into a
 * server that behaves like production over real HTTP.
 *
 * <p>These are the tests that justify the whole fixture. {@link #mapsExceptionThroughRealDefaultMapper()}
 * in particular proves that a harness outside {@code dev.vertique.rest.jaxrs} gets the framework's
 * <em>real</em> {@code DefaultExceptionMapper} — the entire capability that widening
 * {@code RestModule.defaultExceptionMapper()} to {@code public} bought. With that capability supplied
 * by this module instead, restoring the method to package-private becomes possible.
 *
 * <p>The second group covers the <b>ROOT middleware tier</b>. {@code JaxRsRouterMount} installs only
 * the API-scoped middlewares; the ROOT-scoped ones — the default scope — are installed above the
 * mount by {@code HttpVerticle}, so {@link RestTestMounts#startServer} has to reproduce that step or
 * the framework's root pipeline never runs. {@link #requestContextLifecycleIsAvailableToInterceptors()}
 * pins the concrete consequence: {@code RequestLocaleInterceptor} and {@code WebSocketEndpointRegistrar}
 * both fail the request when {@code RequestContextLifecycle.fromRoutingContext} throws.
 *
 * <p>The third group asserts the <b>built-in</b> ROOT middlewares over the wire — the ones
 * {@code RestCoreModule} contributes whether or not a test asks for them, and therefore the ones a
 * fixture that installs only the API tier drops without any test noticing. Two of the five are not
 * covered here and cannot be without new surface: {@code RestRequestCompletionEmitter} is observable
 * only through a {@code RestRequestCompletedListener}, for which {@link RestTestContributions} has no
 * seam, and {@code ContextualLoggingMiddleware} writes only to MDC, which is a no-op with no SLF4J
 * provider on this module's test classpath.
 *
 * <p>Requests are issued through a {@link WebClient} rather than a raw {@code HttpClient}
 * deliberately: a raw {@code HttpClientResponse} discards body buffers that arrive before a body
 * handler is attached, so under load {@code body()} can succeed with zero bytes while the status code
 * is correct (issue #167). Three tests here assert on the response body — the production encoder's
 * raw string, the shouting encoder's output, and the mapped problem detail — so a silently emptied
 * body would be reported as an encoder or exception-mapper defect that did not happen. A
 * {@link WebClient} aggregates the body into its {@code HttpResponse} before completing the send, so
 * the race is closed by construction rather than by every author remembering an idiom.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class RestTestMountsIT {

    /** Bound for every awaited request/response round trip. */
    private static final long ASYNC_TIMEOUT_SECONDS = 5;

    /** Generous bound for the blocking server start — this test is not probing the timeout path. */
    private static final Duration START_TIMEOUT = Duration.ofSeconds(10);

    /** Response header stamped by the ROOT-scoped middleware stand-in. */
    private static final String ROOT_HEADER = "X-Root-Middleware";

    /** Response header stamped by the API-scoped middleware stand-in. */
    private static final String API_HEADER = "X-Api-Middleware";

    /**
     * The request-id header {@code CorrelationIngressMiddleware} reads and echoes. Spelled out rather
     * than read from {@code CorrelationIngressConfig.defaults()} so the assertion pins the wire
     * contract a consumer sees, not whatever the config happens to say.
     */
    private static final String REQUEST_ID_HEADER = "X-Request-Id";

    private static Vertx vertx;
    private static WebClient client;

    private HttpServer server;

    /**
     * Creates the class-scoped {@link WebClient}. It is bound to a static field so
     * {@link #tearDownClient} can close it; an unbound client can never be closed at all.
     *
     * @param injectedVertx the class-scoped Vert.x instance injected by vertx-junit5
     */
    @BeforeAll
    static void setUpClient(Vertx injectedVertx) {
        vertx = injectedVertx;
        // Redirects off: parity with the raw client; WebClient forwards Authorization across 3xx.
        client = WebClient.create(vertx, new WebClientOptions().setFollowRedirects(false));
    }

    /**
     * Closes the shared {@link WebClient} before the extension-owned {@link Vertx} instance is closed.
     *
     * <p>{@link WebClient#close()} is {@code void}, unlike {@code HttpClient.close()}: it returns once
     * the underlying client has been asked to close, so there is no future to await here.
     *
     * @param ctx the test context used for async teardown assertion
     */
    @AfterAll
    static void tearDownClient(VertxTestContext ctx) {
        if (client != null) {
            client.close();
        }
        ctx.completeNow();
    }

    @AfterEach
    void closeServer() throws Exception {
        if (server != null) {
            server.close().toCompletionStage().toCompletableFuture().get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            server = null;
        }
    }

    // --- Production fidelity over the wire ---

    @Test
    @DisplayName("a mounted resource is served end-to-end and encoded by the production StringBodyEncoder")
    void servesRequestThroughRealMount() throws Exception {
        startServer(RestTestContributions.none());

        HttpResult result = get("/fixture/echo");

        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(result.contentType()).contains("text/plain");
        assertThat(result.body())
                .as("the production StringBodyEncoder writes the raw string; the JSON fallback would quote it")
                .isEqualTo("hello");
    }

    @Test
    @DisplayName("an exception from a mounted resource is mapped by RestModule's real DefaultExceptionMapper")
    void mapsExceptionThroughRealDefaultMapper() throws Exception {
        startServer(RestTestContributions.none());

        HttpResult result = get("/fixture/missing");

        assertThat(result.statusCode())
                .as("dev.vertique.core.exception.NotFoundException maps to 404 only in RestModule's real mapper")
                .isEqualTo(404);
        assertThat(result.contentType()).contains("application/problem+json");
        assertThat(result.body()).contains("no such widget");
    }

    @Test
    @DisplayName("the response serializer selects a contributed encoder over the production default")
    void serializerSelectsContributedEncoderOverProductionDefault() throws Exception {
        startServer(RestTestContributions.builder()
                .addResponseBodyEncoder(new ShoutingStringEncoder())
                .build());

        HttpResult result = get("/fixture/echo");

        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(result.body())
                .as("the serializer must iterate the same sorted encoder list the mount factory received")
                .isEqualTo("HELLO");
        assertThat(result.contentType()).contains("text/plain");
    }

    // --- ROOT middleware installation ---

    @Test
    @DisplayName("a contributed ROOT-scoped middleware runs for a request served through startServer")
    void rootScopedMiddlewaresAreInstalled() throws Exception {
        // ROOT is what Middleware.scope() defaults to, so this is the scope a contributed middleware
        // gets when its author never thinks about the question — and a fixture that installs only the
        // API tier discards every one of them silently.
        startServer(RestTestContributions.builder()
                .addMiddleware(new HeaderStampingMiddleware(ROOT_HEADER, MiddlewareScope.ROOT))
                .build());

        HttpResult result = get("/fixture/echo");

        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(result.header(ROOT_HEADER))
                .as("the ROOT tier belongs on the root router above the mount, as HttpVerticle installs it")
                .isEqualTo("ran");
    }

    @Test
    @DisplayName("an API-scoped middleware still runs — the tier JaxRsRouterMount installs is unaffected")
    void apiScopedMiddlewareStillRuns() throws Exception {
        startServer(RestTestContributions.builder()
                .addMiddleware(new HeaderStampingMiddleware(API_HEADER, MiddlewareScope.API))
                .build());

        HttpResult result = get("/fixture/echo");

        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(result.header(API_HEADER)).isEqualTo("ran");
    }

    @Test
    @DisplayName("RequestContextLifecycle resolves for a request interceptor instead of failing the request")
    void requestContextLifecycleIsAvailableToInterceptors() throws Exception {
        // Reproduces RequestLocaleInterceptor.beforeRequest verbatim: resolve the ROOT-installed
        // lifecycle handle, register a cleanup on it, and fail the request if that throws.
        LifecycleProbeInterceptor probe = new LifecycleProbeInterceptor();
        startServer(RestTestContributions.builder().addRequestInterceptor(probe).build());

        HttpResult result = get("/fixture/echo");

        assertThat(probe.duplicatedContext())
                .as("Vert.x Web duplicates the context for every request on its own; RequestContextLifecycle "
                        + "stores a handle and adds an end handler, and never calls duplicate()")
                .isTrue();
        assertThat(probe.lifecycleFailure())
                .as("fromRoutingContext must resolve — RequestLocaleInterceptor and WebSocketEndpointRegistrar "
                        + "fail the request outright when it throws")
                .isNull();
        assertThat(result.statusCode()).isEqualTo(200);
        probe.awaitCleanup();
    }

    // --- Built-in ROOT middlewares ---

    @Test
    @DisplayName("DefaultHeadersMiddleware stamps the framework's default security headers")
    void defaultHeadersMiddlewareStampsSecurityHeaders() throws Exception {
        // DefaultHeadersMiddleware is ROOT-scoped and contributed by RestCoreModule, so nothing a
        // test does puts it there — a fixture that installs only the API tier serves this request
        // with none of these headers.
        startServer(RestTestContributions.none());

        HttpResult result = get("/fixture/echo");

        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(result.header("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(result.header("X-Frame-Options")).isEqualTo("DENY");
        assertThat(result.header("Cache-Control")).isEqualTo("no-store");
    }

    @Test
    @DisplayName("CorrelationIngressMiddleware echoes a generated request id when none is supplied")
    void correlationIngressEchoesGeneratedRequestId() throws Exception {
        startServer(RestTestContributions.none());

        HttpResult result = get("/fixture/echo");

        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(result.header(REQUEST_ID_HEADER))
                .as("echoRequestId defaults to true and the id is generated when the header is absent")
                .isNotBlank();
    }

    @Test
    @DisplayName("CorrelationIngressMiddleware echoes a supplied request id verbatim")
    void correlationIngressEchoesSuppliedRequestId() throws Exception {
        // The generated-id assertion above would also pass if something simply stamped a constant.
        // Echoing an inbound value back proves the middleware read the request: the default policy is
        // REPLACE_WITH_GENERATED, which only replaces values that fail the header validator, so a
        // valid supplied id survives.
        startServer(RestTestContributions.none());
        String supplied = "fixture-request-id-42";

        HttpResult result = get("/fixture/echo", Map.of(REQUEST_ID_HEADER, supplied));

        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(result.header(REQUEST_ID_HEADER)).isEqualTo(supplied);
    }

    @Test
    @DisplayName("ROOT middlewares execute in OrderedExtension order, not set or priority order")
    void middlewaresRunInOrderedExtensionOrder() throws Exception {
        // Phase dominates priority in OrderedExtension.comparator(), so these three priorities are
        // deliberately in the opposite order from the expected execution order: a priority-only sort
        // reverses the result. Their hashCodes force the underlying HashSet's iteration order to be
        // that same reversed sequence, so dropping the sort entirely is caught too.
        List<String> executed = new CopyOnWriteArrayList<>();
        startServer(RestTestContributions.builder()
                .addMiddleware(new OrderRecordingMiddleware("first", ExtensionPhase.SYSTEM_FIRST, 1_000, 3, executed))
                .addMiddleware(new OrderRecordingMiddleware("second", ExtensionPhase.APPLICATION, -1_000, 2, executed))
                .addMiddleware(new OrderRecordingMiddleware("third", ExtensionPhase.SYSTEM_LAST, -5_000, 1, executed))
                .build());

        HttpResult result = get("/fixture/echo");

        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(executed)
                .as("the root install duplicates HttpVerticle's three lines; this is the drift guard on them")
                .containsExactly("first", "second", "third");
    }

    // --- Helpers ---

    /**
     * Starts a server over the fixture graph and records it for teardown.
     *
     * @param contributions the additive contributions the graph is built with
     */
    private void startServer(RestTestContributions contributions) {
        FixtureSelfTestComponent component =
                DaggerFixtureSelfTestComponent.factory().create(vertx, noneStrategyConfig(), contributions);
        server = RestTestMounts.startServerBlocking(
                vertx, component.testMount(), Set.of(new FixtureResource()), START_TIMEOUT);
    }

    /**
     * Issues a header-free GET against the running server and awaits the full response.
     *
     * @param path the request path
     * @return the status, headers, and body of the response
     * @throws Exception when the round trip fails or times out
     */
    private HttpResult get(String path) throws Exception {
        return get(path, Map.of());
    }

    /**
     * Issues a GET against the running server and awaits the full response.
     *
     * <p>{@code putHeader} is the right call here even though {@link HttpRequest} makes it REPLACE
     * rather than append: {@code headers} carries at most one value per name, so no caller depends on
     * a repeated header reaching the server.
     *
     * @param path    the request path
     * @param headers the request headers to send
     * @return the status, headers, and body of the response
     * @throws Exception when the round trip fails or times out
     */
    private HttpResult get(String path, Map<String, String> headers) throws Exception {
        HttpRequest<Buffer> request = client.get(server.actualPort(), "127.0.0.1", path);
        headers.forEach(request::putHeader);
        return request.send()
                .map(response -> {
                    Map<String, String> responseHeaders = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                    response.headers().forEach(entry -> responseHeaders.put(entry.getKey(), entry.getValue()));
                    return new HttpResult(
                            response.statusCode(), responseHeaders, String.valueOf(response.bodyAsString()));
                })
                .toCompletionStage()
                .toCompletableFuture()
                .get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Returns a fresh configuration selecting the {@code none} validation strategy, which a graph
     * carrying no validation module must set explicitly.
     *
     * @return the configuration object
     */
    private static JsonObject noneStrategyConfig() {
        return new JsonObject().put("jaxrs", new JsonObject().put("validationStrategy", "none"));
    }

    /**
     * The parts of an HTTP response these tests assert on.
     *
     * <p>The body is read through {@code bodyAsString()} and wrapped in {@code String.valueOf}: a
     * {@link WebClient} reports an empty body as {@code null} where the raw client reported a
     * zero-length buffer. No response asserted on here is legitimately empty — every one carries the
     * fixture resource's text or a mapped problem detail — so the wrapper only keeps an unexpected
     * empty body a legible assertion failure instead of an NPE.
     *
     * @param statusCode the response status code
     * @param headers    the response headers, keyed case-insensitively as HTTP requires
     * @param body       the response body decoded as a string, or {@code "null"} when there was none
     */
    private record HttpResult(int statusCode, Map<String, String> headers, String body) {

        /**
         * Returns the {@code Content-Type} header.
         *
         * @return the content type, or {@code null} when unset
         */
        String contentType() {
            return header("Content-Type");
        }

        /**
         * Returns a single response header.
         *
         * @param name the header name; matched case-insensitively
         * @return the header value, or {@code null} when unset
         */
        String header(String name) {
            return headers.get(name);
        }
    }

    /** JAX-RS resource exposing one success path and one exception path. */
    @Path("/fixture")
    public static class FixtureResource {

        /**
         * Returns a constant body encoded by whichever {@code String} encoder ranks first.
         *
         * @return the literal {@code "hello"}
         */
        @GET
        @Path("/echo")
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "mountsEcho")
        public String echo() {
            return "hello";
        }

        /**
         * Always fails with the framework's semantic not-found root.
         *
         * @return never returns
         */
        @GET
        @Path("/missing")
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "mountsMissing")
        public String missing() {
            throw new NotFoundException("no such widget");
        }
    }

    /**
     * Contributed {@code String} encoder at priority {@code 900}, which out-ranks the framework
     * default at {@code 1000}. Its output is deliberately distinguishable on the wire.
     */
    private static final class ShoutingStringEncoder implements ResponseBodyEncoder {

        @Override
        public boolean canEncode(Class<?> entityType, String contentType) {
            return entityType == String.class;
        }

        @Override
        public SerializedBody encode(RoutingContext ctx, Response response, Object entity) {
            String shouted = String.valueOf(entity).toUpperCase(Locale.ROOT);
            return new BufferedBody(Buffer.buffer(shouted), "text/plain", null);
        }

        @Override
        public int priority() {
            return 900;
        }
    }

    // --- Middleware stand-ins ---

    /** Middleware that stamps a fixed response header, making its execution observable on the wire. */
    private static final class HeaderStampingMiddleware implements Middleware {

        private final String headerName;
        private final MiddlewareScope scope;

        /**
         * Creates the stand-in.
         *
         * @param headerName the header to stamp
         * @param scope      the scope to report
         */
        HeaderStampingMiddleware(String headerName, MiddlewareScope scope) {
            this.headerName = headerName;
            this.scope = scope;
        }

        @Override
        public void handle(RoutingContext ctx) {
            ctx.response().putHeader(headerName, "ran");
            ctx.next();
        }

        @Override
        public MiddlewareScope scope() {
            return scope;
        }

        @Override
        public int priority() {
            return 0;
        }
    }

    /**
     * ROOT middleware that appends its name to a shared log when it runs, so the installation site's
     * ordering is observable.
     *
     * <p>{@link #orderKey()} is overridden because the default is the class name, which ties for
     * three instances of one class — exactly the case {@code OrderedExtension#orderKey()} tells
     * repeat registrations to override. {@link #hashCode()} is fixed so the middleware set's own
     * iteration order is deterministic <em>and deliberately wrong</em>, which is what lets the
     * ordering assertion fail reliably if the installation site stops sorting.
     */
    private static final class OrderRecordingMiddleware implements Middleware {

        private final String name;
        private final ExtensionPhase phase;
        private final int priority;
        private final int hash;
        private final List<String> executed;

        /**
         * Creates the stand-in.
         *
         * @param name     the name appended to {@code executed}, also used as the tie-break order key
         * @param phase    the phase to report
         * @param priority the priority to report
         * @param hash     the fixed hash code pinning this instance's position in the middleware set
         * @param executed the shared execution log
         */
        OrderRecordingMiddleware(String name, ExtensionPhase phase, int priority, int hash, List<String> executed) {
            this.name = name;
            this.phase = phase;
            this.priority = priority;
            this.hash = hash;
            this.executed = executed;
        }

        @Override
        public void handle(RoutingContext ctx) {
            executed.add(name);
            ctx.next();
        }

        @Override
        public ExtensionPhase phase() {
            return phase;
        }

        @Override
        public int priority() {
            return priority;
        }

        @Override
        public String orderKey() {
            return name;
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object other) {
            return this == other;
        }
    }

    /**
     * Request interceptor reproducing {@code RequestLocaleInterceptor.beforeRequest} — the seam the
     * ROOT-installed {@code RequestContextLifecycle} exists to serve — and recording what it observed.
     */
    private static final class LifecycleProbeInterceptor implements RequestInterceptor {

        /** Whether the interceptor ran on a duplicated Vert.x context; {@code null} until it runs. */
        private final AtomicReference<Boolean> duplicatedContext = new AtomicReference<>();

        /** The message of the failure {@code fromRoutingContext} raised, or {@code null} on success. */
        private final AtomicReference<String> lifecycleFailure = new AtomicReference<>();

        /** Completed by the cleanup this interceptor registers on the lifecycle handle. */
        private final CompletableFuture<Void> cleanupRan = new CompletableFuture<>();

        @Override
        public Future<Void> beforeRequest(RoutingContext rc) {
            Context current = Vertx.currentContext();
            duplicatedContext.set(current instanceof ContextInternal internal && internal.isDuplicate());
            try {
                // The Runnable cast is required: Handle.onClose is overloaded on two functional
                // interfaces (Runnable and ContextHolder.Scope), so a bare lambda is ambiguous.
                Runnable cleanup = () -> cleanupRan.complete(null);
                RequestContextLifecycle.fromRoutingContext(rc).onClose(cleanup);
            } catch (RuntimeException e) {
                lifecycleFailure.set(e.getMessage());
                return Future.failedFuture(e);
            }
            return Future.succeededFuture();
        }

        /**
         * Returns whether the interceptor observed a duplicated Vert.x context.
         *
         * @return the observation, or {@code null} if the interceptor never ran
         */
        Boolean duplicatedContext() {
            return duplicatedContext.get();
        }

        /**
         * Returns the lifecycle-resolution failure message.
         *
         * @return the message, or {@code null} when the handle resolved
         */
        String lifecycleFailure() {
            return lifecycleFailure.get();
        }

        /**
         * Awaits the registered cleanup, which the lifecycle drives from the request's end handler.
         *
         * @throws Exception if the cleanup does not run within the async bound
         */
        void awaitCleanup() throws Exception {
            cleanupRan.get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
    }
}
