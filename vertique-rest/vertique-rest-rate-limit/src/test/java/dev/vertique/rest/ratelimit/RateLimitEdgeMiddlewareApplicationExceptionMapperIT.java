// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dagger.BindsInstance;
import dagger.Component;
import dev.vertique.core.VertxConfig;
import dev.vertique.ratelimit.GreedyRateLimitRefill;
import dev.vertique.ratelimit.RateLimitFailureMode;
import dev.vertique.ratelimit.RateLimitKey;
import dev.vertique.ratelimit.RateLimitMode;
import dev.vertique.ratelimit.RateLimitPolicy;
import dev.vertique.ratelimit.RateLimiters;
import dev.vertique.ratelimit.TokenBucketRateLimit;
import dev.vertique.ratelimit.exception.RateLimitExceededException;
import dev.vertique.ratelimit.exception.RateLimitUnavailableException;
import dev.vertique.ratelimit.spi.RateLimitBackend;
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
 * T019 established: an edge denial (429/503) renders through the application's own {@code
 * ExceptionMapper<RateLimitExceededException>}/{@code ExceptionMapper<RateLimitUnavailableException>}
 * contribution — the same mapper-resolution seam the {@code execute()}/{@code @RateLimited} path
 * already uses — instead of a hardcoded body; with no application override the response carries the
 * built-in {@code RateLimitExceptionMapper} shape; a mapper that itself throws falls back to that
 * same built-in response.
 *
 * <p>T020 re-points this proof at the real, mounted {@code JaxRsRouterMount} pipeline (via {@link
 * RestTestMounts}) rather than a bare, failure-handler-less {@code RouterMount}: the middleware now
 * delegates via {@code ctx.fail}, so the response is no longer byte-identical to a hand-built {@link
 * dev.vertique.rest.core.ProblemDetail} — {@code ErrorPipeline} enriches it with {@code instance}
 * (the request path), which the middleware's own T019 rendering never did. See {@code
 * RateLimitEdgeFullPipelineRenderingIT} for the full-pipeline dressing proof (transformResponse,
 * ErrorInterceptor.afterMapping, the new framework default); this class stays focused on precedence:
 * built-in vs. application-contributed mapper, and the mapper-throw fallback.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class RateLimitEdgeMiddlewareApplicationExceptionMapperIT {

    private static final String QUOTA_POLICY = "edge-mapper-quota";
    private static final String UNAVAILABLE_POLICY = "edge-mapper-unavailable";
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

    @Test
    void shouldRenderTheBuiltInMapperBodyWhenNoApplicationMapperOverridesQuotaExceeded(
            Vertx vertx, VertxTestContext ctx) {
        RestTestContributions contributions = RestTestContributions.builder()
                .addExceptionMapper(new RateLimitExceptionMapper.Exceeded())
                .addExceptionMapper(new RateLimitExceptionMapper.Unavailable())
                .build();

        deployQuotaExceeded(vertx, contributions)
                .compose(ignored -> post(vertx))
                .onComplete(ctx.succeeding(response -> {
                    assertEquals(429, response.statusCode());
                    assertEquals("application/problem+json", response.getHeader("Content-Type"));
                    assertEquals("no-store", response.getHeader("Cache-Control"));
                    JsonObject body = response.bodyAsJsonObject();
                    assertEquals(429, body.getInteger("status"));
                    assertNull(body.getString("detail"), "the built-in mapper's body carries no detail");
                    assertEquals(
                            ECHO_PATH,
                            body.getString("instance"),
                            "ErrorPipeline enriches instance from the request path — T019's own hand-built "
                                    + "rendering never reached ErrorPipeline at all");
                    ctx.completeNow();
                }));
    }

    @Test
    void shouldRenderTheBuiltInMapperBodyWhenNoApplicationMapperOverridesBackendFailureClosed(
            Vertx vertx, VertxTestContext ctx) {
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

    /**
     * Red before the T019 fix: the edge middleware wrote a hardcoded {@link
     * dev.vertique.rest.core.ProblemDetail} body regardless of any application-contributed mapper.
     * Green after the fix, and still green under T020's {@code ctx.fail} delegation: the custom
     * mapper's distinct response is served, resolved through the mount's own real registry.
     */
    @Test
    void shouldRenderTheApplicationContributedMapperInsteadOfTheBuiltInBodyForQuotaExceeded(
            Vertx vertx, VertxTestContext ctx) {
        RestTestContributions contributions = RestTestContributions.builder()
                .addExceptionMapper(CUSTOM_EXCEEDED_MAPPER)
                .build();

        deployQuotaExceeded(vertx, contributions)
                .compose(ignored -> post(vertx))
                .onComplete(ctx.succeeding(response -> {
                    assertEquals(429, response.statusCode());
                    assertEquals("text/plain", response.getHeader("Content-Type"));
                    assertEquals("yes", response.getHeader("X-Custom-Limited"));
                    assertEquals("custom-limited", response.bodyAsString());
                    ctx.completeNow();
                }));
    }

    @Test
    void shouldRenderTheApplicationContributedMapperInsteadOfTheBuiltInBodyForBackendFailureClosed(
            Vertx vertx, VertxTestContext ctx) {
        RestTestContributions contributions = RestTestContributions.builder()
                .addExceptionMapper(CUSTOM_UNAVAILABLE_MAPPER)
                .build();

        deployBackendFailureClosed(vertx, contributions)
                .compose(ignored -> post(vertx))
                .onComplete(ctx.succeeding(response -> {
                    assertEquals(503, response.statusCode());
                    assertEquals("text/plain", response.getHeader("Content-Type"));
                    assertEquals("yes", response.getHeader("X-Custom-Unavailable"));
                    assertEquals("custom-unavailable", response.bodyAsString());
                    ctx.completeNow();
                }));
    }

    /**
     * T020 behavior change (documented consequence, "execute() parity"): a throwing application
     * mapper now falls through to {@code ResponsePipeline}'s own bare-metal {@code 500} fallback —
     * the same fallback a throwing mapper for an {@code execute()} exception hits — rather than
     * T019's fixed {@code 429}/{@code 503} fallback body (the middleware no longer builds any
     * response itself, so there is no local fallback left to fall back to).
     */
    @Test
    void shouldFallThroughToThePipelines500WhenTheApplicationMapperThrowsForQuotaExceeded(
            Vertx vertx, VertxTestContext ctx) {
        RestTestContributions contributions = RestTestContributions.builder()
                .addExceptionMapper(new ThrowingExceededMapper())
                .build();

        deployQuotaExceeded(vertx, contributions)
                .compose(ignored -> post(vertx))
                .onComplete(ctx.succeeding(response -> {
                    assertEquals(500, response.statusCode());
                    ctx.completeNow();
                }));
    }

    @Test
    void shouldFallThroughToThePipelines500WhenTheApplicationMapperThrowsForBackendFailureClosed(
            Vertx vertx, VertxTestContext ctx) {
        RestTestContributions contributions = RestTestContributions.builder()
                .addExceptionMapper(new ThrowingUnavailableMapper())
                .build();

        deployBackendFailureClosed(vertx, contributions)
                .compose(ignored -> post(vertx))
                .onComplete(ctx.succeeding(response -> {
                    assertEquals(500, response.statusCode());
                    ctx.completeNow();
                }));
    }

    // Named classes, not lambdas: ExceptionMapperResolver resolves a mapper's exception type by
    // reflecting on its generic interfaces (dev.vertique.core.util.TypeResolver), which a lambda's
    // synthetic class does not reliably expose — exactly the same convention the framework's own
    // RateLimitExceptionMapper.Exceeded/.Unavailable and the customresponse example's
    // CategorizedExceptionMapper already follow.
    private static final ExceptionMapper<RateLimitExceededException> CUSTOM_EXCEEDED_MAPPER =
            new CustomExceededMapper();

    private static final ExceptionMapper<RateLimitUnavailableException> CUSTOM_UNAVAILABLE_MAPPER =
            new CustomUnavailableMapper();

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

    private static final class CustomUnavailableMapper implements ExceptionMapper<RateLimitUnavailableException> {
        @Override
        public Response toResponse(RateLimitUnavailableException exception) {
            return Response.status(503)
                    .header("X-Custom-Unavailable", "yes")
                    .type("text/plain")
                    .entity("custom-unavailable")
                    .build();
        }
    }

    private static final class ThrowingExceededMapper implements ExceptionMapper<RateLimitExceededException> {
        @Override
        public Response toResponse(RateLimitExceededException exception) {
            throw new IllegalStateException("synthetic mapper failure");
        }
    }

    private static final class ThrowingUnavailableMapper implements ExceptionMapper<RateLimitUnavailableException> {
        @Override
        public Response toResponse(RateLimitUnavailableException exception) {
            throw new IllegalStateException("synthetic mapper failure");
        }
    }

    // --- Deployment helpers ---

    private Future<Void> deployQuotaExceeded(Vertx vertx, RestTestContributions contributions) {
        RateLimitPolicy policy =
                RateLimitEdgeTestFixture.tokenBucketPolicy(QUOTA_POLICY, 1L, RateLimitFailureMode.CLOSED);
        RateLimiters rateLimiters = RateLimitEdgeTestFixture.rateLimiters(vertx, policy);
        RateLimitEdgeMiddleware middleware =
                new RateLimitEdgeMiddleware(globalRuleConfig(QUOTA_POLICY), rateLimiters, false);
        return rateLimiters
                .limiter(QUOTA_POLICY)
                .acquire(RateLimitKey.global())
                .compose(ignored -> deploy(vertx, middleware, contributions));
    }

    private Future<Void> deployBackendFailureClosed(Vertx vertx, RestTestContributions contributions) {
        RateLimitPolicy policy = new RateLimitPolicy(
                UNAVAILABLE_POLICY,
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
                new RateLimiters(Set.of(policy), backends, null, vertx, Set.of(), java.util.Optional::empty, true);
        RateLimitEdgeMiddleware middleware =
                new RateLimitEdgeMiddleware(globalRuleConfig(UNAVAILABLE_POLICY), rateLimiters, false);
        return deploy(vertx, middleware, contributions);
    }

    private Future<Void> deploy(Vertx vertx, RateLimitEdgeMiddleware middleware, RestTestContributions contributions) {
        RestTestContributions.Builder builder = RestTestContributions.builder();
        contributions.middlewares().forEach(builder::addMiddleware);
        contributions.exceptionMappers().forEach(builder::addExceptionMapper);
        builder.addMiddleware(middleware);

        RateLimitEdgeMapperMountComponent component =
                DaggerRateLimitEdgeMiddlewareApplicationExceptionMapperIT_RateLimitEdgeMapperMountComponent.factory()
                        .create(vertx, noneStrategyConfig(), builder.build());
        RestTestMount mount = component.testMount();
        return RestTestMounts.startServer(vertx, mount, Set.of(new EchoResource()))
                .map(started -> {
                    server = started;
                    client = WebClient.create(vertx);
                    return (Void) null;
                });
    }

    private Future<io.vertx.ext.web.client.HttpResponse<Buffer>> post(Vertx vertx) {
        return client.post(server.actualPort(), "127.0.0.1", ECHO_PATH).sendBuffer(Buffer.buffer("payload"));
    }

    private static JsonObject noneStrategyConfig() {
        return new JsonObject().put("jaxrs", new JsonObject().put("validationStrategy", "none"));
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

    /** Mounted resource the edge middleware must never let a denied request reach. */
    @jakarta.ws.rs.Path("/api")
    public static final class EchoResource {

        @jakarta.ws.rs.POST
        @jakarta.ws.rs.Path("/echo")
        @io.swagger.v3.oas.annotations.Operation(operationId = "mapperPrecedenceEcho")
        public String echo() {
            return "ok";
        }
    }

    /**
     * This IT's own consumer-shaped Dagger component over {@link RestTestFixtureModule} — the real
     * {@code JaxRsRouterMount.Factory} plus the graph's complete middleware set, exactly as {@code
     * RestTestMountsIT}'s self-test component is built.
     */
    @Singleton
    @Component(modules = {RestTestFixtureModule.class, RestTestNoSecurityModule.class})
    interface RateLimitEdgeMapperMountComponent {

        RestTestMount testMount();

        @Component.Factory
        interface Factory {
            RateLimitEdgeMapperMountComponent create(
                    @BindsInstance Vertx vertx,
                    @BindsInstance @VertxConfig JsonObject config,
                    @BindsInstance RestTestContributions contributions);
        }
    }
}
