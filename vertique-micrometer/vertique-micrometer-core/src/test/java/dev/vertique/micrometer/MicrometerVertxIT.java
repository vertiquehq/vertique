// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer;

import static org.junit.jupiter.api.Assertions.*;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Vertx;
import io.vertx.core.VertxBuilder;
import io.vertx.core.VertxOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Integration tests for {@link MicrometerMetricsContributor} end-to-end wiring with a real Vert.x
 * instance.
 *
 * <p>Verifies that when the contributor is used with a real {@link VertxBuilder}, HTTP server
 * metrics are reported to the contributed {@link io.micrometer.core.instrument.MeterRegistry}, and
 * that the zero-backend path is genuinely inert.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class MicrometerVertxIT {

    private Vertx vertx;

    @AfterEach
    void tearDown(VertxTestContext ctx) {
        MeterRegistryHolder.resetForTests();
        if (vertx != null) {
            vertx.close().onComplete(ar -> ctx.completeNow());
        } else {
            ctx.completeNow();
        }
    }

    // --- Test: active path with fake provider → vertx.http. meters appear ---

    @Test
    @DisplayName("active path: after HTTP request, vertx.http. meter appears in fake provider's registry")
    void activePathHttpMetersRecorded(VertxTestContext ctx) throws Exception {
        SimpleMeterRegistry simpleRegistry = new SimpleMeterRegistry();
        MicrometerMetricsContributorTest.FakeProvider fakeProvider =
                new MicrometerMetricsContributorTest.FakeProvider("test", simpleRegistry);

        MicrometerMetricsContributor contributor = new MicrometerMetricsContributor(List.of(fakeProvider));

        VertxOptions options = new VertxOptions();
        VertxBuilder builder = Vertx.builder().with(options);
        JsonObject config = new JsonObject().put("metrics", new JsonObject());

        contributor.contribute(builder, fakeContext(config, options));

        // Build Vert.x: builder already has withMetrics configured and options has MicrometerMetricsOptions set
        vertx = builder.build();

        // Start an HTTP server on port 0
        vertx.createHttpServer()
                .requestHandler(req -> req.response().end("OK"))
                .listen(0, "127.0.0.1")
                .compose(server -> {
                    int port = server.actualPort();
                    // Issue one HTTP request using the vertx web client
                    return vertx.createHttpClient()
                            .request(io.vertx.core.http.HttpMethod.GET, port, "127.0.0.1", "/")
                            .compose(req -> req.send())
                            .compose(resp -> {
                                assertEquals(200, resp.statusCode());
                                return io.vertx.core.Future.succeededFuture();
                            });
                })
                .compose(v -> {
                    // Poll until a vertx.http. meter appears, up to 3 seconds with 100ms ticks
                    return pollUntilHttpMeter(simpleRegistry, 30, 100);
                })
                .onSuccess(v -> ctx.completeNow())
                .onFailure(ctx::failNow);
    }

    // --- Test: zero-backend path → no vertx. meters ---

    @Test
    @DisplayName("zero-backend path: no vertx. meters appear after HTTP request")
    void zeroBackendPathNoVertxMeters(VertxTestContext ctx) throws Exception {
        // Zero providers — contributor is fully inert
        MicrometerMetricsContributor contributor = new MicrometerMetricsContributor(List.of());

        VertxOptions options = new VertxOptions();
        VertxBuilder builder = Vertx.builder().with(options);
        JsonObject config = new JsonObject().put("metrics", new JsonObject());

        VertxBuilder result = contributor.contribute(builder, fakeContext(config, options));
        vertx = result.build();

        // Start server, make a request, assert no vertx. meters
        vertx.createHttpServer()
                .requestHandler(req -> req.response().end("OK"))
                .listen(0, "127.0.0.1")
                .compose(server -> {
                    int port = server.actualPort();
                    return vertx.createHttpClient()
                            .request(io.vertx.core.http.HttpMethod.GET, port, "127.0.0.1", "/")
                            .compose(req -> req.send())
                            .compose(resp -> {
                                assertEquals(200, resp.statusCode());
                                return io.vertx.core.Future.succeededFuture();
                            });
                })
                .onSuccess(v -> {
                    // After the request, verify no vertx.* meters in the global holder
                    MeterRegistry registry = MeterRegistryHolder.registry();
                    boolean hasVertxMeters = registry.getMeters().stream()
                            .anyMatch(m -> m.getId().getName().startsWith("vertx."));
                    assertFalse(hasVertxMeters, "No vertx. meters must appear when zero backends are provided");
                    ctx.completeNow();
                })
                .onFailure(ctx::failNow);
    }

    // --- Helpers ---

    /**
     * Polls the given registry until a meter with a name starting with {@code "vertx.http."} is
     * found, or until the deadline is reached.
     *
     * @param registry   the registry to poll
     * @param maxTries   maximum number of poll iterations
     * @param delayMs    delay between iterations in milliseconds
     * @return a {@link io.vertx.core.Future} that succeeds when the meter is found, fails if maxTries exceeded
     */
    private io.vertx.core.Future<Void> pollUntilHttpMeter(SimpleMeterRegistry registry, int maxTries, long delayMs) {
        if (maxTries <= 0) {
            return io.vertx.core.Future.failedFuture(
                    new AssertionError("No vertx.http. meter found in the SimpleMeterRegistry after polling"));
        }
        boolean found =
                registry.getMeters().stream().anyMatch(m -> m.getId().getName().startsWith("vertx.http."));
        if (found) {
            return io.vertx.core.Future.succeededFuture();
        }
        // Schedule next poll via Vert.x timer (not Thread.sleep — never block event loop)
        return io.vertx.core.Future.<Void>future(promise -> vertx.setTimer(delayMs, id -> promise.complete()))
                .compose(v -> pollUntilHttpMeter(registry, maxTries - 1, delayMs));
    }

    /**
     * Creates a minimal {@link dev.vertique.bootstrap.BootstrapContext} backed by the given config
     * and options.
     *
     * @param config  the bootstrap configuration JSON
     * @param options the live {@link VertxOptions} instance
     * @return a fake bootstrap context
     */
    private static dev.vertique.bootstrap.BootstrapContext fakeContext(JsonObject config, VertxOptions options) {
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
