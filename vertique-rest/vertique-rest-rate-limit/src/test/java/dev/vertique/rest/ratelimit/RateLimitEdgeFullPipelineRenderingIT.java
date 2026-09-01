// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dagger.BindsInstance;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.core.VertxConfig;
import dev.vertique.ratelimit.GreedyRateLimitRefill;
import dev.vertique.ratelimit.LocalRateLimitBackendFactory;
import dev.vertique.ratelimit.RateLimitFailureMode;
import dev.vertique.ratelimit.RateLimitKey;
import dev.vertique.ratelimit.RateLimitMode;
import dev.vertique.ratelimit.RateLimitPolicy;
import dev.vertique.ratelimit.RateLimiters;
import dev.vertique.ratelimit.TokenBucketRateLimit;
import dev.vertique.ratelimit.exception.RateLimitExceededException;
import dev.vertique.ratelimit.spi.RateLimitBackend;
import dev.vertique.rest.core.interceptor.ErrorInterceptor;
import dev.vertique.rest.test.RestTestContributions;
import dev.vertique.rest.test.RestTestFixtureModule;
import dev.vertique.rest.test.RestTestMount;
import dev.vertique.rest.test.RestTestMounts;
import dev.vertique.rest.test.RestTestNoSecurityModule;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import jakarta.inject.Singleton;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * T020 slice 3: proves an edge rate-limit denial renders through the <em>full</em> JAX-RS failure
 * pipeline ({@code JaxRsRouterMount.handleFailure} → {@code ErrorPipeline} →
 * {@code ResponsePipeline}) — the same pipeline a resource method's own thrown exception traverses —
 * rather than the middleware's own hand-built response (T019).
 *
 * <p>Uses {@link RestTestMounts#startServer} (a real, mounted {@code JaxRsRouterMount}, both
 * middleware tiers installed) so the request actually crosses the {@code JaxRsRouterMount} failure
 * boundary the way {@code ctx.fail(status, cause)} delegation depends on. Red under T019's
 * direct-write middleware (which resolves denials through its own locally-built
 * {@code ExceptionMapperRegistry} and writes the wire response by hand, never touching
 * {@code ErrorPipeline}/{@code ResponsePipeline} at all); green once the middleware delegates via
 * {@code ctx.fail}.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class RateLimitEdgeFullPipelineRenderingIT {

    private static final String TRANSFORM_RESPONSE_HEADER = "X-Transform-Response";
    private static final String AFTER_MAPPING_HEADER = "X-After-Mapping";
    private static final String ECHO_PATH = "/api/echo";

    private WebClient client;
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.close();
        }
    }

    // --- Row: packaged mapper installed — full pipeline dressing applied ---

    @Test
    void packagedMapperRendersThroughFullPipelineWithTransformResponseInstanceAndAfterMapping(
            Vertx vertx, VertxTestContext ctx) {
        RestTestContributions contributions = RestTestContributions.builder()
                .addExceptionMapper(new RateLimitExceptionMapper.Exceeded())
                .addExceptionMapper(new RateLimitExceptionMapper.Unavailable())
                .addRequestInterceptor(new TransformResponseMarkerInterceptor())
                .build();

        deployQuotaExceeded(vertx, contributions)
                .compose(ignored -> post(vertx))
                .onComplete(ctx.succeeding(response -> {
                    assertEquals(429, response.statusCode());
                    assertEquals(
                            "yes",
                            response.getHeader(TRANSFORM_RESPONSE_HEADER),
                            "transformResponse must run for an edge denial, exactly as it does for execute()");
                    assertEquals(
                            "yes",
                            response.getHeader(AFTER_MAPPING_HEADER),
                            "ErrorInterceptor.afterMapping must run for an edge denial");
                    assertEquals("application/problem+json", response.getHeader("Content-Type"));
                    assertEquals("no-store", response.getHeader("Cache-Control"));
                    JsonObject body = response.bodyAsJsonObject();
                    assertEquals(429, body.getInteger("status"));
                    assertEquals(
                            ECHO_PATH,
                            body.getString("instance"),
                            "ProblemDetail.instance must be enriched from the request path by ErrorPipeline, "
                                    + "which never ran under the middleware's own hand-built rendering");
                    ctx.completeNow();
                }));
    }

    @Test
    void packagedMapperMirrorsBackendFailureClosedThroughFullPipeline(Vertx vertx, VertxTestContext ctx) {
        RestTestContributions contributions = RestTestContributions.builder()
                .addExceptionMapper(new RateLimitExceptionMapper.Exceeded())
                .addExceptionMapper(new RateLimitExceptionMapper.Unavailable())
                .build();

        deployBackendFailureClosed(vertx, contributions)
                .compose(ignored -> post(vertx))
                .onComplete(ctx.succeeding(response -> {
                    assertEquals(503, response.statusCode());
                    assertEquals("application/problem+json", response.getHeader("Content-Type"));
                    assertEquals("no-store", response.getHeader("Cache-Control"));
                    assertNull(response.getHeader("Retry-After"));
                    JsonObject body = response.bodyAsJsonObject();
                    assertEquals(503, body.getInteger("status"));
                    assertEquals(ECHO_PATH, body.getString("instance"));
                    ctx.completeNow();
                }));
    }

    // --- Row: application-contributed mapper controls the response ---

    @Test
    void applicationContributedMapperControlsTheResponse(Vertx vertx, VertxTestContext ctx) {
        RestTestContributions contributions = RestTestContributions.builder()
                .addExceptionMapper(new CustomExceededMapper())
                .addRequestInterceptor(new TransformResponseMarkerInterceptor())
                .build();

        deployQuotaExceeded(vertx, contributions)
                .compose(ignored -> post(vertx))
                .onComplete(ctx.succeeding(response -> {
                    assertEquals(429, response.statusCode());
                    assertEquals("text/plain", response.getHeader("Content-Type"));
                    assertEquals("yes", response.getHeader("X-Custom-Limited"));
                    assertEquals("custom-limited", response.bodyAsString());
                    assertEquals(
                            "yes",
                            response.getHeader(TRANSFORM_RESPONSE_HEADER),
                            "the application mapper's response must still cross ResponsePipeline.sendResponse "
                                    + "(transformResponse), proving it came from the real mount registry, not a "
                                    + "hand-written wire write");
                    ctx.completeNow();
                }));
    }

    // --- Row: a throwing application mapper falls through to the pipeline's own 500 (execute() parity) ---

    @Test
    void throwingApplicationMapperFallsThroughToThePipelines500(Vertx vertx, VertxTestContext ctx) {
        RestTestContributions contributions = RestTestContributions.builder()
                .addExceptionMapper(new ThrowingExceededMapper())
                .build();

        deployQuotaExceeded(vertx, contributions)
                .compose(ignored -> post(vertx))
                .onComplete(ctx.succeeding(response -> {
                    assertEquals(
                            500,
                            response.statusCode(),
                            "a throwing application mapper must fall through to ResponsePipeline's own "
                                    + "bare-metal 500 — the same fallback a throwing mapper for an execute() "
                                    + "exception hits — never the middleware's fixed 429 fallback");
                    ctx.completeNow();
                }));
    }

    // --- Row: no rate-limit-specific mapper installed — the new framework default renders it ---

    @Test
    void noRateLimitMapperRendersThroughTheNewFrameworkDefault(Vertx vertx, VertxTestContext ctx) {
        deployQuotaExceeded(vertx, RestTestContributions.none())
                .compose(ignored -> post(vertx))
                .onComplete(ctx.succeeding(response -> {
                    assertEquals(429, response.statusCode());
                    assertEquals(
                            "application/problem+json",
                            response.getHeader("Content-Type"),
                            "with no rate-limit-specific mapper installed, RestModule's new "
                                    + "TooManyRequestsException -> 429 default mapping must still render it");
                    assertTrue(
                            response.getHeader("Retry-After") != null
                                    && Integer.parseInt(response.getHeader("Retry-After")) >= 1,
                            "the framework default must thread Retry-After from the decision, via "
                                    + "TooManyRequestsException.retryAfter()");
                    ctx.completeNow();
                }));
    }

    @Test
    void noRateLimitMapperRendersBackendFailureClosedThroughTheExistingUnavailableDefault(
            Vertx vertx, VertxTestContext ctx) {
        deployBackendFailureClosed(vertx, RestTestContributions.none())
                .compose(ignored -> post(vertx))
                .onComplete(ctx.succeeding(response -> {
                    assertEquals(503, response.statusCode());
                    assertEquals("application/problem+json", response.getHeader("Content-Type"));
                    ctx.completeNow();
                }));
    }

    // --- Row: decision-less absent-origin + CLOSED renders the default 503 problem body ---

    @Test
    void decisionLessAbsentOriginClosedRendersTheDefault503ProblemBody(Vertx vertx, VertxTestContext ctx) {
        String policy = "edge-fullpipeline-absent-origin-closed";
        RateLimiters rateLimiters = rateLimiters(vertx, tokenBucketPolicy(policy, 1L, RateLimitFailureMode.CLOSED));
        RateLimitEdgeRule ipRule = new RateLimitEdgeRule(
                policy,
                java.util.List.of(RateLimitEdgeKeyDimension.IP),
                java.util.Optional.empty(),
                java.util.OptionalLong.empty(),
                MissingDimensionPolicy.SHARED_BUCKET,
                java.util.OptionalInt.of(RateLimitEdgeRule.DEFAULT_IPV6_PREFIX_BITS));
        RateLimitEdgeConfig config = new RateLimitEdgeConfig(true, java.util.List.of(ipRule), "/*");
        // originCaptureBound=true simulates the startup check having already passed, but no
        // OriginCaptureMiddleware is actually deployed below — RequestOrigin is absent at request
        // time, forcing the decision-less absent-origin+CLOSED path.
        RateLimitEdgeMiddleware middleware = new RateLimitEdgeMiddleware(config, rateLimiters, true);

        deploy(vertx, middleware, RestTestContributions.none())
                .compose(ignored -> post(vertx))
                .onComplete(ctx.succeeding(response -> {
                    assertEquals(503, response.statusCode());
                    assertEquals("application/problem+json", response.getHeader("Content-Type"));
                    JsonObject body = response.bodyAsJsonObject();
                    assertEquals(503, body.getInteger("status"));
                    assertEquals(ECHO_PATH, body.getString("instance"));
                    ctx.completeNow();
                }));
    }

    // --- Deployment helpers ---

    private Future<Void> deployQuotaExceeded(Vertx vertx, RestTestContributions contributions) {
        String policy = "edge-fullpipeline-quota";
        RateLimiters rateLimiters = rateLimiters(vertx, tokenBucketPolicy(policy, 1L, RateLimitFailureMode.CLOSED));
        RateLimitEdgeMiddleware middleware = new RateLimitEdgeMiddleware(globalRuleConfig(policy), rateLimiters, false);
        return rateLimiters
                .limiter(policy)
                .acquire(RateLimitKey.global())
                .compose(ignored -> deploy(vertx, middleware, contributions));
    }

    private Future<Void> deployBackendFailureClosed(Vertx vertx, RestTestContributions contributions) {
        String policy = "edge-fullpipeline-unavailable";
        RateLimitPolicy policyDef = new RateLimitPolicy(
                policy,
                true,
                RateLimitMode.LOCAL,
                RateLimitFailureMode.CLOSED,
                "r1",
                1L,
                new TokenBucketRateLimit(1L, new GreedyRateLimitRefill(1, Duration.ofDays(1))));
        RateLimitBackend failingBackend =
                request -> Future.failedFuture(new RuntimeException("synthetic backend failure"));
        Map<RateLimitMode, RateLimitBackend> backends = Map.of(RateLimitMode.LOCAL, failingBackend);
        RateLimiters rateLimiters =
                new RateLimiters(Set.of(policyDef), backends, null, vertx, Set.of(), java.util.Optional::empty, true);
        RateLimitEdgeMiddleware middleware = new RateLimitEdgeMiddleware(globalRuleConfig(policy), rateLimiters, false);
        return deploy(vertx, middleware, contributions);
    }

    private Future<Void> deploy(Vertx vertx, RateLimitEdgeMiddleware middleware, RestTestContributions contributions) {
        RestTestContributions withMiddleware =
                RestTestContributions.builder().addMiddleware(middleware).build();
        RestTestContributions merged = merge(contributions, withMiddleware);
        RateLimitEdgeFullPipelineComponent component =
                DaggerRateLimitEdgeFullPipelineRenderingIT_RateLimitEdgeFullPipelineComponent.factory()
                        .create(vertx, noneStrategyConfig(), merged);
        RestTestMount mount = component.testMount();
        return RestTestMounts.startServer(vertx, mount, Set.of(new EchoResource()))
                .map(started -> {
                    server = started;
                    client = WebClient.create(vertx);
                    return (Void) null;
                });
    }

    private static RestTestContributions merge(RestTestContributions a, RestTestContributions b) {
        RestTestContributions.Builder builder = RestTestContributions.builder();
        a.middlewares().forEach(builder::addMiddleware);
        b.middlewares().forEach(builder::addMiddleware);
        a.requestInterceptors().forEach(builder::addRequestInterceptor);
        b.requestInterceptors().forEach(builder::addRequestInterceptor);
        a.exceptionMappers().forEach(builder::addExceptionMapper);
        b.exceptionMappers().forEach(builder::addExceptionMapper);
        return builder.build();
    }

    private Future<HttpResponse<Buffer>> post(Vertx vertx) {
        return client.post(server.actualPort(), "127.0.0.1", ECHO_PATH).sendBuffer(Buffer.buffer("payload"));
    }

    private static JsonObject noneStrategyConfig() {
        return new JsonObject().put("jaxrs", new JsonObject().put("validationStrategy", "none"));
    }

    private static RateLimiters rateLimiters(Vertx vertx, RateLimitPolicy policyDef) {
        Map<RateLimitMode, RateLimitBackend> backends =
                Map.of(RateLimitMode.LOCAL, LocalRateLimitBackendFactory.local(name -> 100_000L, 60_000L));
        return new RateLimiters(Set.of(policyDef), backends, null, vertx, Set.of(), java.util.Optional::empty, true);
    }

    private static RateLimitPolicy tokenBucketPolicy(String name, long capacity, RateLimitFailureMode failureMode) {
        return new RateLimitPolicy(
                name,
                true,
                RateLimitMode.LOCAL,
                failureMode,
                "r1",
                1L,
                new TokenBucketRateLimit(capacity, new GreedyRateLimitRefill(1, Duration.ofDays(1))));
    }

    private static RateLimitEdgeConfig globalRuleConfig(String policyName) {
        RateLimitEdgeRule globalRule = new RateLimitEdgeRule(
                policyName,
                java.util.List.of(RateLimitEdgeKeyDimension.GLOBAL),
                java.util.Optional.empty(),
                java.util.OptionalLong.empty(),
                MissingDimensionPolicy.SHARED_BUCKET,
                java.util.OptionalInt.empty());
        return new RateLimitEdgeConfig(true, java.util.List.of(globalRule), "/*");
    }

    // --- JAX-RS resource mounted behind the edge middleware ---

    /** Mounted resource the edge middleware must never let a denied request reach. */
    @jakarta.ws.rs.Path("/api")
    public static final class EchoResource {

        @jakarta.ws.rs.POST
        @jakarta.ws.rs.Path("/echo")
        @io.swagger.v3.oas.annotations.Operation(operationId = "fullPipelineEcho")
        public String echo() {
            return "ok";
        }
    }

    // --- Test-only interceptors/mappers ---

    /** Stamps a header from {@code transformResponse} — proves the response passed through ResponsePipeline. */
    private static final class TransformResponseMarkerInterceptor
            implements dev.vertique.rest.core.interceptor.RequestInterceptor {
        @Override
        public Future<Response> transformResponse(io.vertx.ext.web.RoutingContext rc, Response response) {
            return Future.succeededFuture(Response.fromResponse(response)
                    .header(TRANSFORM_RESPONSE_HEADER, "yes")
                    .build());
        }
    }

    private static final class CustomExceededMapper implements ExceptionMapper<RateLimitExceededException> {
        @Override
        public Response toResponse(RateLimitExceededException exception) {
            return Response.status(429)
                    .header("X-Custom-Limited", "yes")
                    .type("text/plain")
                    .entity("custom-limited")
                    .build();
        }
    }

    private static final class ThrowingExceededMapper implements ExceptionMapper<RateLimitExceededException> {
        @Override
        public Response toResponse(RateLimitExceededException exception) {
            throw new IllegalStateException("synthetic mapper failure");
        }
    }

    // --- Local Dagger wiring: real RestModule graph plus an ErrorInterceptor marker ---

    /**
     * Contributes a fixed {@link ErrorInterceptor} stamping {@link #AFTER_MAPPING_HEADER} from
     * {@code afterMapping} — {@link RestTestContributions} has no seam for {@code ErrorInterceptor}
     * (it is not among the seams any in-repo harness has needed until this proof), so this local
     * module contributes it directly into the framework's {@code Set<ErrorInterceptor>} multibinding
     * ({@code RestCoreModule#errorInterceptors()}).
     */
    @Module
    abstract static class AfterMappingMarkerModule {

        private AfterMappingMarkerModule() {}

        @Provides
        @IntoSet
        static ErrorInterceptor afterMappingMarkerInterceptor() {
            return new ErrorInterceptor() {
                @Override
                public Future<Response> afterMapping(io.vertx.ext.web.RoutingContext rc, Response response) {
                    return Future.succeededFuture(Response.fromResponse(response)
                            .header(AFTER_MAPPING_HEADER, "yes")
                            .build());
                }
            };
        }
    }

    /**
     * This IT's own consumer-shaped Dagger component over {@link RestTestFixtureModule} — the real
     * {@code JaxRsRouterMount.Factory} plus the graph's complete middleware set, exactly as
     * {@code RestTestMountsIT}'s self-test component is built, plus {@link AfterMappingMarkerModule}.
     */
    @Singleton
    @Component(modules = {RestTestFixtureModule.class, RestTestNoSecurityModule.class, AfterMappingMarkerModule.class})
    interface RateLimitEdgeFullPipelineComponent {

        RestTestMount testMount();

        @Component.Factory
        interface Factory {
            RateLimitEdgeFullPipelineComponent create(
                    @BindsInstance Vertx vertx,
                    @BindsInstance @VertxConfig JsonObject config,
                    @BindsInstance RestTestContributions contributions);
        }
    }
}
