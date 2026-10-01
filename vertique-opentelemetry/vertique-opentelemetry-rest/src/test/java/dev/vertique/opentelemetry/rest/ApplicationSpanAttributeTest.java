// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.opentelemetry.rest;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vertique.rest.core.router.OperationRegistrationContext;
import dev.vertique.rest.core.routing.RestOperationDescriptor;
import dev.vertique.rest.core.routing.RouteRegistration;
import dev.vertique.rest.core.security.SecurityPolicy;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.tracing.opentelemetry.OpenTelemetryOptions;
import io.vertx.tracing.opentelemetry.OpenTelemetryTracingFactory;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Proves that {@link ServerSpanEnrichmentContributor} labels the server span of an operation that
 * belongs to an application with the application's name, and leaves every other span unlabelled.
 *
 * <p>A traced Vert.x wired to an OpenTelemetry SDK with an in-memory exporter serves three
 * operations on a plain {@link Router}. Each route mounts the handler the contributor registers
 * through {@link ServerSpanEnrichmentContributor#contribute} (captured from a mocked
 * {@link RouteRegistration}) followed by a terminal 200 handler:
 *
 * <ul>
 *   <li>{@code mgmt-status} on {@code /mgmt/status}, belonging to application {@code mgmt};</li>
 *   <li>{@code legacy-status} on {@code /legacy/status}, belonging to no application;</li>
 *   <li>{@code mgmt-secured} on {@code /mgmt/secured}, belonging to application {@code mgmt} and
 *       guarded by a stub authentication handler mounted <em>before</em> the contributor's
 *       handler, as in the production chain. An unauthenticated request is rejected with 401
 *       before the contributor runs, so its span carries neither the application nor the
 *       operation attribute: the documented limitation this test pins.</li>
 * </ul>
 *
 * <p>Attribute keys are hand-written literals so the expectation never copies production state.
 * The requests are issued one at a time, and the exporter is reset before each, so every request's
 * span is found as the single exported server span without relying on any attribute under test.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class ApplicationSpanAttributeTest {

    private static final AttributeKey<String> APPLICATION_NAME = AttributeKey.stringKey("vertique.application.name");
    private static final AttributeKey<String> OPERATION_ID = AttributeKey.stringKey("vertique.operation.id");
    private static final AttributeKey<String> SERVICE_NAME = AttributeKey.stringKey("service.name");

    /** The service name this test sets on the SDK resource; no span may report another. */
    private static final String TEST_SERVICE_NAME = "application-span-test";

    // --- Shared per-test state ---

    private HttpServer server;

    /** The client teardown awaits; exists only to keep an awaitable close handle. */
    private HttpClient rawClient;

    /** What the test issues its requests through; wraps {@link #rawClient}. */
    private WebClient httpClient;

    private Vertx tracedVertx;
    private OpenTelemetrySdk sdk;
    private InMemorySpanExporter exporter;

    /**
     * Closes the server and client while the traced Vert.x event loop is alive, then the traced
     * Vert.x itself; the SDK is closed up front.
     *
     * @param ctx the test context used to signal teardown completion
     */
    @AfterEach
    void tearDown(VertxTestContext ctx) {
        Future<?> serverClose = server != null ? server.close() : Future.succeededFuture();
        Future<?> clientClose = rawClient != null ? rawClient.close() : Future.succeededFuture();
        if (sdk != null) {
            sdk.close();
        }
        GlobalOpenTelemetry.resetForTest();
        // The server/client closes resolve on the traced Vert.x event loop, so join them before
        // closing that Vert.x; closing it inside the join races the loop shutdown.
        Future.join(serverClose, clientClose).onComplete(ar -> {
            if (tracedVertx != null) {
                tracedVertx.close();
            }
            ctx.completeNow();
        });
    }

    // --- Test ---

    @Test
    @DisplayName("an application operation's span carries its name; an unowned or rejected request's span does not")
    void applicationOperationSpansCarryTheName(VertxTestContext ctx) {
        // Given: a traced server with an application-owned, an unowned, and an authenticated operation
        buildTracedVertx();
        Router router = Router.router(tracedVertx);
        router.get("/mgmt/status").handler(contributedHandler("mgmt-status", mgmtStatusDescriptor()));
        router.get("/mgmt/status").handler(ApplicationSpanAttributeTest::ok);
        router.get("/legacy/status").handler(contributedHandler("legacy-status", legacyStatusDescriptor()));
        router.get("/legacy/status").handler(ApplicationSpanAttributeTest::ok);
        router.get("/mgmt/secured").handler(ApplicationSpanAttributeTest::stubAuthentication);
        router.get("/mgmt/secured").handler(contributedHandler("mgmt-secured", mgmtSecuredDescriptor()));
        router.get("/mgmt/secured").handler(ApplicationSpanAttributeTest::ok);

        // When: each operation is called once, the secured one without credentials
        startServer(router)
                .compose(port -> exchange(port, "/mgmt/status").compose(mgmtStatus -> exchange(port, "/legacy/status")
                        .compose(legacyStatus -> exchange(port, "/mgmt/secured")
                                .map(mgmtSecured -> List.of(mgmtStatus, legacyStatus, mgmtSecured)))))
                .onComplete(ctx.succeeding(exchanges -> {
                    Exchange mgmtStatus = exchanges.get(0);
                    Exchange legacyStatus = exchanges.get(1);
                    Exchange mgmtSecured = exchanges.get(2);

                    // Then
                    ctx.verify(() -> assertAll(
                            () -> assertEquals(200, mgmtStatus.status(), "mgmt-status must answer 200"),
                            () -> assertEquals(
                                    "mgmt",
                                    mgmtStatus.span().getAttributes().get(APPLICATION_NAME),
                                    "mgmt-status span must carry vertique.application.name=mgmt"),
                            () -> assertEquals(
                                    "mgmt-status",
                                    mgmtStatus.span().getAttributes().get(OPERATION_ID),
                                    "mgmt-status span must carry vertique.operation.id=mgmt-status"),
                            () -> assertEquals(200, legacyStatus.status(), "legacy-status must answer 200"),
                            () -> assertNull(
                                    legacyStatus.span().getAttributes().get(APPLICATION_NAME),
                                    "legacy-status span must carry no vertique.application.name"),
                            () -> assertEquals(
                                    "legacy-status",
                                    legacyStatus.span().getAttributes().get(OPERATION_ID),
                                    "legacy-status span must carry vertique.operation.id=legacy-status"),
                            () -> assertEquals(
                                    401, mgmtSecured.status(), "unauthenticated mgmt-secured must answer 401"),
                            () -> assertNull(
                                    mgmtSecured.span().getAttributes().get(APPLICATION_NAME),
                                    "rejected mgmt-secured span must carry no vertique.application.name"),
                            () -> assertNull(
                                    mgmtSecured.span().getAttributes().get(OPERATION_ID),
                                    "rejected mgmt-secured span must carry no vertique.operation.id"),
                            () -> assertEquals(
                                    TEST_SERVICE_NAME,
                                    mgmtStatus.span().getResource().getAttribute(SERVICE_NAME),
                                    "mgmt-status span service.name must be unchanged"),
                            () -> assertEquals(
                                    TEST_SERVICE_NAME,
                                    legacyStatus.span().getResource().getAttribute(SERVICE_NAME),
                                    "legacy-status span service.name must be unchanged"),
                            () -> assertEquals(
                                    TEST_SERVICE_NAME,
                                    mgmtSecured.span().getResource().getAttribute(SERVICE_NAME),
                                    "mgmt-secured span service.name must be unchanged")));
                    ctx.completeNow();
                }));
    }

    // --- Descriptor factories: one per operation ---

    private static RestOperationDescriptor mgmtStatusDescriptor() {
        RestOperationDescriptor descriptor = mock(RestOperationDescriptor.class);
        when(descriptor.operationId()).thenReturn("mgmt-status");
        when(descriptor.routeTemplate()).thenReturn("/mgmt/status");
        when(descriptor.applicationName()).thenReturn("mgmt");
        return descriptor;
    }

    private static RestOperationDescriptor mgmtSecuredDescriptor() {
        RestOperationDescriptor descriptor = mock(RestOperationDescriptor.class);
        when(descriptor.operationId()).thenReturn("mgmt-secured");
        when(descriptor.routeTemplate()).thenReturn("/mgmt/secured");
        when(descriptor.applicationName()).thenReturn("mgmt");
        return descriptor;
    }

    /**
     * Describes an operation of no application. A Mockito mock never runs the interface's default
     * {@code applicationName()}, so the default's {@code null} is stubbed explicitly.
     */
    private static RestOperationDescriptor legacyStatusDescriptor() {
        RestOperationDescriptor descriptor = mock(RestOperationDescriptor.class);
        when(descriptor.operationId()).thenReturn("legacy-status");
        when(descriptor.routeTemplate()).thenReturn("/legacy/status");
        when(descriptor.applicationName()).thenReturn(null);
        return descriptor;
    }

    // --- Helpers ---

    /** One request's observed response status and the server span it exported. */
    private record Exchange(int status, SpanData span) {}

    /**
     * Builds an {@link OpenTelemetrySdk} whose resource names {@link #TEST_SERVICE_NAME}, with
     * {@link Sampler#alwaysOn()} and W3C propagation, and wires it into a new traced Vert.x.
     */
    private void buildTracedVertx() {
        exporter = InMemorySpanExporter.create();
        Resource resource =
                Resource.getDefault().merge(Resource.create(Attributes.of(SERVICE_NAME, TEST_SERVICE_NAME)));
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .setResource(resource)
                .setSampler(Sampler.alwaysOn())
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build();
        VertxOptions options = new VertxOptions().setTracingOptions(new OpenTelemetryOptions());
        tracedVertx = Vertx.builder()
                .with(options)
                .withTracer(new OpenTelemetryTracingFactory(sdk))
                .build();
    }

    /**
     * Drives {@link ServerSpanEnrichmentContributor#contribute} for one operation and returns the
     * handler it registered on the mocked {@link RouteRegistration}.
     *
     * @param operationId the operation's id
     * @param descriptor  the operation's descriptor
     * @return the contributed handler
     */
    @SuppressWarnings("unchecked")
    private static Handler<RoutingContext> contributedHandler(String operationId, RestOperationDescriptor descriptor) {
        RouteRegistration route = mock(RouteRegistration.class);
        AtomicReference<Handler<RoutingContext>> handlerRef = new AtomicReference<>();
        when(route.addHandler(any())).thenAnswer(invocation -> {
            handlerRef.set(invocation.getArgument(0));
            return route;
        });

        OperationRegistrationContext context =
                new OperationRegistrationContext(operationId, new SecurityPolicy.None(), descriptor, route);
        new ServerSpanEnrichmentContributor().contribute(context);

        Handler<RoutingContext> handler = handlerRef.get();
        assertNotNull(handler, "contribute() must register a handler for " + operationId);
        return handler;
    }

    /** Stub authentication: rejects with 401 unless the {@code X-Test-User} header is present. */
    private static void stubAuthentication(RoutingContext rc) {
        if (rc.request().getHeader("X-Test-User") == null) {
            rc.response().setStatusCode(401).end();
            return;
        }
        rc.next();
    }

    /** Terminal handler answering 200. */
    private static void ok(RoutingContext rc) {
        rc.response().setStatusCode(200).end("ok");
    }

    /**
     * Starts the server on {@code 127.0.0.1} and an ephemeral port, and creates the client.
     *
     * @param router the request handler
     * @return a future resolving to the bound port
     */
    private Future<Integer> startServer(Router router) {
        return tracedVertx
                .createHttpServer()
                .requestHandler(router)
                .listen(0, "127.0.0.1")
                .map(s -> {
                    this.server = s;
                    // The raw client exists only to give teardown an awaitable close handle.
                    this.rawClient = tracedVertx.createHttpClient();
                    this.httpClient = WebClient.wrap(rawClient, new WebClientOptions().setFollowRedirects(false));
                    return s.actualPort();
                });
    }

    /**
     * Issues one GET to {@code 127.0.0.1} with an empty exporter and returns its status together
     * with the single server span it exported.
     *
     * @param port the server port
     * @param path the request path
     * @return a future resolving to the request's exchange
     */
    private Future<Exchange> exchange(int port, String path) {
        exporter.reset();
        return httpClient.get(port, "127.0.0.1", path).send().compose(response -> pollForServerSpan(path, 40, 50)
                .map(span -> new Exchange(response.statusCode(), span)));
    }

    /**
     * Polls the exporter until exactly one finished server span is present and returns it.
     *
     * @param path        the request path, for the failure message
     * @param maxAttempts maximum polling attempts before failing
     * @param delayMs     delay between attempts in milliseconds
     * @return a future resolving to the request's server span
     */
    private Future<SpanData> pollForServerSpan(String path, int maxAttempts, long delayMs) {
        List<SpanData> serverSpans = exporter.getFinishedSpanItems().stream()
                .filter(s -> s.getKind() == SpanKind.SERVER)
                .toList();
        if (serverSpans.size() == 1) {
            return Future.succeededFuture(serverSpans.get(0));
        }
        if (serverSpans.size() > 1 || maxAttempts <= 0) {
            return Future.failedFuture(
                    new AssertionError("expected exactly one server span for " + path + "; got " + serverSpans.size()));
        }
        return Future.<Void>future(p -> tracedVertx.setTimer(delayMs, id -> p.complete()))
                .compose(v -> pollForServerSpan(path, maxAttempts - 1, delayMs));
    }
}
