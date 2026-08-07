// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer.prometheus;

import static org.junit.jupiter.api.Assertions.*;

import dev.vertique.management.ManagementConfig;
import dev.vertique.management.ManagementVerticle;
import dev.vertique.micrometer.MicrometerMetricsContributor;
import io.micrometer.core.instrument.Gauge;
import io.vertx.core.Vertx;
import io.vertx.core.VertxBuilder;
import io.vertx.core.VertxOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Integration test that verifies the end-to-end Prometheus scrape pipeline.
 *
 * <p>Verifies:
 * <ol>
 *   <li>{@link MicrometerMetricsContributor} discovers {@link PrometheusMeterRegistryProvider}
 *       via ServiceLoader and wires it as the Micrometer backend.</li>
 *   <li>A real Vert.x HTTP server emits {@code vertx_http_*} meters into the registry.</li>
 *   <li>{@link PrometheusScrapeEndpoint} exposes those meters at {@code GET /metrics} on the
 *       management server.</li>
 *   <li>OpenMetrics format is served when requested via {@code Accept} header.</li>
 *   <li>{@code /metrics} is NOT served on the application server port (NFR-TEL-005): the app
 *       server is a Router-based server that returns 404 for un-registered paths.</li>
 *   <li>The scrape body is rendered on a Vert.x worker thread, not the event loop.</li>
 * </ol>
 *
 * <p>All test methods share a single bootstrapped Vert.x + ManagementVerticle stack set up in
 * {@link #setUp(VertxTestContext)} and torn down in {@link #tearDown(VertxTestContext)}.
 * This is necessary because {@link PrometheusBackend} is a JVM-wide static — failsafe forks one
 * JVM per module, so all tests in this class share one process lifetime.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class PrometheusScrapeIT {

    private static Vertx vertx;
    private static int managementPort;
    private static int appServerPort;
    private static MicrometerMetricsContributor contributor;

    /**
     * Bootstraps the shared stack:
     * <ol>
     *   <li>Creates {@link MicrometerMetricsContributor} (ServiceLoader discovers
     *       {@link PrometheusMeterRegistryProvider}).</li>
     *   <li>Builds a Vertx with Micrometer wired.</li>
     *   <li>Starts an application HTTP server (Router-based, so unknown paths → 404) on port 0
     *       and fires one request to {@code /} to generate HTTP metrics.</li>
     *   <li>Deploys {@link ManagementVerticle} on port 0 with a {@link PrometheusScrapeEndpoint}.</li>
     * </ol>
     *
     * @param ctx the Vert.x test context
     * @throws Exception if bootstrap fails
     */
    @BeforeAll
    static void setUp(VertxTestContext ctx) throws Exception {
        contributor = new MicrometerMetricsContributor();

        VertxOptions options = new VertxOptions();
        VertxBuilder builder = Vertx.builder().with(options);
        JsonObject config = new JsonObject().put("metrics", new JsonObject());

        contributor.contribute(builder, fakeBootstrapContext(config, options));

        vertx = builder.build();

        // Start a Router-based application HTTP server on port 0.
        // A Router returns 404 for paths not explicitly registered (NFR-TEL-005).
        Router appRouter = Router.router(vertx);
        appRouter.get("/").handler(rc -> rc.response().end("OK"));

        vertx.createHttpServer()
                .requestHandler(appRouter)
                .listen(0, "127.0.0.1")
                .compose(server -> {
                    appServerPort = server.actualPort();
                    // Fire one request to / to seed HTTP server metrics
                    return vertx.createHttpClient()
                            .request(HttpMethod.GET, appServerPort, "127.0.0.1", "/")
                            .compose(req -> req.send())
                            .mapEmpty();
                })
                // Deploy ManagementVerticle on port 0 with the scrape endpoint
                .compose(ignored -> {
                    PrometheusScrapeConfig scrapeConfig =
                            PrometheusScrapeConfig.builder().build();
                    PrometheusScrapeEndpoint scrapeEndpoint =
                            new PrometheusScrapeEndpoint(vertx, Optional.empty(), scrapeConfig);
                    ManagementConfig mgmtConfig =
                            ManagementConfig.builder().port(0).host("127.0.0.1").build();
                    ManagementVerticle mgmtVerticle =
                            new ManagementVerticle(Set.of(), Set.of(), mgmtConfig, Set.of(scrapeEndpoint));
                    return vertx.deployVerticle(mgmtVerticle);
                })
                .onSuccess(id -> {
                    managementPort =
                            (int) vertx.sharedData().getLocalMap("vertique").get("management.port");
                    ctx.completeNow();
                })
                .onFailure(ctx::failNow);
    }

    /**
     * Tears down the shared stack.
     *
     * @param ctx the Vert.x test context
     */
    @AfterAll
    static void tearDown(VertxTestContext ctx) {
        if (contributor != null) {
            contributor.onShutdown();
        }
        if (vertx != null) {
            vertx.close().onComplete(ar -> ctx.completeNow());
        } else {
            ctx.completeNow();
        }
    }

    // --- Tests ---

    @Test
    @DisplayName("GET /metrics → 200 with vertx_http_ meter in body (poll)")
    void metricsEndpointReturnsVertxHttpMeters(VertxTestContext ctx) {
        pollBodyContains("vertx_http", 30, 100L, ctx);
    }

    @Test
    @DisplayName("GET /metrics → 200 with jvm_ metrics in body (poll)")
    void metricsEndpointContainsJvmMetrics(VertxTestContext ctx) {
        pollBodyContains("jvm_", 20, 100L, ctx);
    }

    @Test
    @DisplayName("GET /metrics with Accept: application/openmetrics-text → openmetrics content-type and '# EOF'")
    void openmetricsAcceptHeader(VertxTestContext ctx) {
        vertx.createHttpClient()
                .request(HttpMethod.GET, managementPort, "127.0.0.1", "/metrics")
                .compose(req -> {
                    req.putHeader("Accept", "application/openmetrics-text; version=1.0.0");
                    return req.send();
                })
                .compose(resp -> resp.body().map(body -> {
                    ctx.verify(() -> {
                        assertEquals(200, resp.statusCode());
                        String ct = resp.getHeader("Content-Type");
                        assertNotNull(ct, "Content-Type must be set");
                        assertTrue(
                                ct.contains("application/openmetrics-text"),
                                "Content-Type must be openmetrics, got: " + ct);
                        assertTrue(body.toString().contains("# EOF"), "OpenMetrics body must contain '# EOF'");
                    });
                    return body;
                }))
                .onSuccess(ignored -> ctx.completeNow())
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("GET /health/live → 200 (health endpoint still works alongside /metrics)")
    void healthLiveStillWorks(VertxTestContext ctx) {
        vertx.createHttpClient()
                .request(HttpMethod.GET, managementPort, "127.0.0.1", "/health/live")
                .compose(req -> req.send())
                .onSuccess(resp -> {
                    ctx.verify(() -> assertEquals(200, resp.statusCode()));
                    ctx.completeNow();
                })
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("NFR-TEL-005: GET /metrics on application port → 404 (Router-based app server)")
    void metricsNotOnApplicationPort(VertxTestContext ctx) {
        vertx.createHttpClient()
                .request(HttpMethod.GET, appServerPort, "127.0.0.1", "/metrics")
                .compose(req -> req.send())
                .onSuccess(resp -> {
                    ctx.verify(() -> assertEquals(404, resp.statusCode(), "app server must return 404 for /metrics"));
                    ctx.completeNow();
                })
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("worker-thread proof: scrape gauge supplier captures non-event-loop thread name")
    void scrapeRunsOnWorkerThread(VertxTestContext ctx) {
        AtomicReference<String> capturedThread = new AtomicReference<>();

        PrometheusBackend.registry()
                .ifPresentOrElse(
                        registry -> Gauge.builder("scrape.thread.probe", () -> {
                                    capturedThread.set(Thread.currentThread().getName());
                                    return 1.0;
                                })
                                .register(registry),
                        () -> ctx.failNow(new AssertionError("Prometheus registry must be present")));

        vertx.createHttpClient()
                .request(HttpMethod.GET, managementPort, "127.0.0.1", "/metrics")
                .compose(req -> req.send())
                .compose(resp -> resp.body())
                // Small delay to ensure the gauge supplier has been invoked
                .compose(body -> io.vertx.core.Future.<Void>future(p -> vertx.setTimer(50, id -> p.complete())))
                .onSuccess(ignored -> {
                    ctx.verify(() -> {
                        String threadName = capturedThread.get();
                        assertNotNull(threadName, "thread name must have been captured during scrape");
                        assertFalse(
                                threadName.contains("eventloop"),
                                "scrape must run on a worker thread, got thread: " + threadName);
                    });
                    ctx.completeNow();
                })
                .onFailure(ctx::failNow);
    }

    // --- Helpers ---

    /**
     * Polls {@code GET /metrics} on the management port until the response body contains
     * {@code substring}, using at most {@code maxTries} attempts with {@code delayMs} between each.
     *
     * <p>Uses Vert.x timers (never blocks the event loop). Calls
     * {@link VertxTestContext#completeNow()} on success, {@link VertxTestContext#failNow(Throwable)}
     * when {@code maxTries} is exhausted.
     *
     * @param substring the string to wait for in the scrape body
     * @param maxTries  maximum poll attempts before failing the test context
     * @param delayMs   delay between attempts in milliseconds
     * @param ctx       the test context
     */
    private void pollBodyContains(String substring, int maxTries, long delayMs, VertxTestContext ctx) {
        doPoll(substring, maxTries, delayMs, ctx);
    }

    /**
     * Recursive Vert.x-timer-based poll implementation.
     *
     * @param substring the string to find
     * @param remaining remaining poll attempts
     * @param delayMs   delay in milliseconds between attempts
     * @param ctx       the test context
     */
    private void doPoll(String substring, int remaining, long delayMs, VertxTestContext ctx) {
        if (remaining <= 0) {
            ctx.failNow(new AssertionError("Timed out waiting for '" + substring + "' in /metrics body"));
            return;
        }
        vertx.createHttpClient()
                .request(HttpMethod.GET, managementPort, "127.0.0.1", "/metrics")
                .compose(req -> req.send())
                .compose(resp -> resp.body())
                .onSuccess(body -> {
                    if (body.toString().contains(substring)) {
                        ctx.completeNow();
                    } else {
                        vertx.setTimer(delayMs, id -> doPoll(substring, remaining - 1, delayMs, ctx));
                    }
                })
                .onFailure(t -> vertx.setTimer(delayMs, id -> doPoll(substring, remaining - 1, delayMs, ctx)));
    }

    /**
     * Creates a minimal {@link dev.vertique.bootstrap.BootstrapContext} from the given config and
     * options.
     *
     * @param config  the application configuration
     * @param options the live {@link VertxOptions} instance
     * @return a fake bootstrap context
     */
    private static dev.vertique.bootstrap.BootstrapContext fakeBootstrapContext(
            JsonObject config, VertxOptions options) {
        return new dev.vertique.bootstrap.BootstrapContext() {
            @Override
            public JsonObject config() {
                return config.copy();
            }

            @Override
            public VertxOptions vertxOptions() {
                return options;
            }
        };
    }
}
