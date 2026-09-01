// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
import dev.vertique.rest.core.ProblemDetail;
import dev.vertique.rest.core.router.HttpVerticle;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.Json;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * T019: an edge denial (429/503) renders through the application's own {@code
 * ExceptionMapper<RateLimitExceededException>}/{@code ExceptionMapper<RateLimitUnavailableException>}
 * contribution — the same mapper-resolution seam the {@code execute()}/{@code @RateLimited} path
 * already uses — instead of a hardcoded body, with no application override the response stays
 * byte-identical to today's built-in {@code RateLimitExceptionMapper} shape, and a mapper that
 * itself throws falls back to that same built-in response (this class's own javadoc, "Denial
 * rendering").
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class RateLimitEdgeMiddlewareApplicationExceptionMapperIT {

    private static final String QUOTA_POLICY = "edge-mapper-quota";
    private static final String UNAVAILABLE_POLICY = "edge-mapper-unavailable";

    private WebClient client;

    // Verticles deployed by the tests below are automatically undeployed when VertxExtension
    // closes the injected Vertx instance after each test; only the directly-created WebClient
    // needs an explicit close.
    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    void shouldRenderTheBuiltInBodyByteIdenticalToTodayWhenNoApplicationMapperOverridesQuotaExceeded(
            Vertx vertx, VertxTestContext ctx) {
        RateLimiters rateLimiters = quotaExceededRateLimiters(vertx);

        rateLimiters
                .limiter(QUOTA_POLICY)
                .acquire(RateLimitKey.global())
                .compose(ignored -> deploy(
                        vertx, quotaMiddleware(rateLimiters, RateLimitEdgeTestFixture.defaultExceptionMappers())))
                .onComplete(ctx.succeeding(port -> post(vertx, port).onComplete(ctx.succeeding(response -> {
                    assertEquals(429, response.statusCode());
                    assertEquals("application/problem+json", response.getHeader("Content-Type"));
                    assertEquals("no-store", response.getHeader("Cache-Control"));
                    assertEquals(Json.encode(ProblemDetail.of(429, null)), response.bodyAsString());
                    ctx.completeNow();
                }))));
    }

    @Test
    void shouldRenderTheBuiltInBodyByteIdenticalToTodayWhenNoApplicationMapperOverridesBackendFailureClosed(
            Vertx vertx, VertxTestContext ctx) {
        RateLimiters rateLimiters = backendFailureClosedRateLimiters(vertx);

        deploy(vertx, unavailableMiddleware(rateLimiters, RateLimitEdgeTestFixture.defaultExceptionMappers()))
                .onComplete(ctx.succeeding(port -> post(vertx, port).onComplete(ctx.succeeding(response -> {
                    assertEquals(503, response.statusCode());
                    assertEquals("application/problem+json", response.getHeader("Content-Type"));
                    assertEquals("no-store", response.getHeader("Cache-Control"));
                    assertNull(response.getHeader("Retry-After"));
                    assertEquals(Json.encode(ProblemDetail.of(503, null)), response.bodyAsString());
                    ctx.completeNow();
                }))));
    }

    /**
     * Red before the fix: the edge middleware wrote a hardcoded {@link ProblemDetail} body
     * regardless of any application-contributed mapper, so this assertion failed against the
     * pre-fix middleware. Green after the fix: the custom mapper's distinct response is served.
     */
    @Test
    void shouldRenderTheApplicationContributedMapperInsteadOfTheBuiltInBodyForQuotaExceeded(
            Vertx vertx, VertxTestContext ctx) {
        RateLimiters rateLimiters = quotaExceededRateLimiters(vertx);
        Set<ExceptionMapper<?>> mappers = Set.of(CUSTOM_EXCEEDED_MAPPER, new RateLimitExceptionMapper.Unavailable());

        rateLimiters
                .limiter(QUOTA_POLICY)
                .acquire(RateLimitKey.global())
                .compose(ignored -> deploy(vertx, quotaMiddleware(rateLimiters, mappers)))
                .onComplete(ctx.succeeding(port -> post(vertx, port).onComplete(ctx.succeeding(response -> {
                    assertEquals(429, response.statusCode());
                    assertEquals("text/plain", response.getHeader("Content-Type"));
                    assertEquals("yes", response.getHeader("X-Custom-Limited"));
                    assertEquals("custom-limited", response.bodyAsString());
                    ctx.completeNow();
                }))));
    }

    @Test
    void shouldRenderTheApplicationContributedMapperInsteadOfTheBuiltInBodyForBackendFailureClosed(
            Vertx vertx, VertxTestContext ctx) {
        RateLimiters rateLimiters = backendFailureClosedRateLimiters(vertx);
        Set<ExceptionMapper<?>> mappers = Set.of(new RateLimitExceptionMapper.Exceeded(), CUSTOM_UNAVAILABLE_MAPPER);

        deploy(vertx, unavailableMiddleware(rateLimiters, mappers))
                .onComplete(ctx.succeeding(port -> post(vertx, port).onComplete(ctx.succeeding(response -> {
                    assertEquals(503, response.statusCode());
                    assertEquals("text/plain", response.getHeader("Content-Type"));
                    assertEquals("yes", response.getHeader("X-Custom-Unavailable"));
                    assertEquals("custom-unavailable", response.bodyAsString());
                    ctx.completeNow();
                }))));
    }

    @Test
    void shouldFallBackToTheBuiltInResponseWhenTheApplicationMapperThrowsForQuotaExceeded(
            Vertx vertx, VertxTestContext ctx) {
        RateLimiters rateLimiters = quotaExceededRateLimiters(vertx);
        Set<ExceptionMapper<?>> mappers =
                Set.of(new ThrowingExceededMapper(), new RateLimitExceptionMapper.Unavailable());

        rateLimiters
                .limiter(QUOTA_POLICY)
                .acquire(RateLimitKey.global())
                .compose(ignored -> deploy(vertx, quotaMiddleware(rateLimiters, mappers)))
                .onComplete(ctx.succeeding(port -> post(vertx, port).onComplete(ctx.succeeding(response -> {
                    assertEquals(429, response.statusCode());
                    assertEquals("application/problem+json", response.getHeader("Content-Type"));
                    assertEquals(Json.encode(ProblemDetail.of(429, null)), response.bodyAsString());
                    ctx.completeNow();
                }))));
    }

    @Test
    void shouldFallBackToTheBuiltInResponseWhenTheApplicationMapperThrowsForBackendFailureClosed(
            Vertx vertx, VertxTestContext ctx) {
        RateLimiters rateLimiters = backendFailureClosedRateLimiters(vertx);
        Set<ExceptionMapper<?>> mappers =
                Set.of(new RateLimitExceptionMapper.Exceeded(), new ThrowingUnavailableMapper());

        deploy(vertx, unavailableMiddleware(rateLimiters, mappers))
                .onComplete(ctx.succeeding(port -> post(vertx, port).onComplete(ctx.succeeding(response -> {
                    assertEquals(503, response.statusCode());
                    assertEquals("application/problem+json", response.getHeader("Content-Type"));
                    assertEquals(Json.encode(ProblemDetail.of(503, null)), response.bodyAsString());
                    ctx.completeNow();
                }))));
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

    /** A single-rule GLOBAL policy over a real LOCAL backend, capacity 1 — the caller pre-consumes the one token via a direct {@code acquire()} to force the next request's rule evaluation into {@code QUOTA_EXCEEDED}. */
    private static RateLimiters quotaExceededRateLimiters(Vertx vertx) {
        RateLimitPolicy policy =
                RateLimitEdgeTestFixture.tokenBucketPolicy(QUOTA_POLICY, 1L, RateLimitFailureMode.CLOSED);
        return RateLimitEdgeTestFixture.rateLimiters(vertx, policy);
    }

    private static RateLimitEdgeMiddleware quotaMiddleware(RateLimiters rateLimiters, Set<ExceptionMapper<?>> mappers) {
        return new RateLimitEdgeMiddleware(globalRuleConfig(QUOTA_POLICY), rateLimiters, false, mappers);
    }

    /**
     * A single-rule GLOBAL policy bound to a backend whose {@code consume(...)} always fails its
     * returned future, with {@code failureMode=CLOSED} — every {@code acquire()} on it classifies
     * {@code BACKEND_FAILURE_CLOSED}, deterministically and without a real Redis/network failure.
     */
    private static RateLimiters backendFailureClosedRateLimiters(Vertx vertx) {
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
        return new RateLimiters(Set.of(policy), backends, null, vertx, Set.of(), Optional::empty, true);
    }

    private static RateLimitEdgeMiddleware unavailableMiddleware(
            RateLimiters rateLimiters, Set<ExceptionMapper<?>> mappers) {
        return new RateLimitEdgeMiddleware(globalRuleConfig(UNAVAILABLE_POLICY), rateLimiters, false, mappers);
    }

    private static RateLimitEdgeConfig globalRuleConfig(String policyName) {
        RateLimitEdgeRule globalRule = new RateLimitEdgeRule(
                policyName,
                List.of(RateLimitEdgeKeyDimension.GLOBAL),
                Optional.empty(),
                OptionalLong.empty(),
                MissingDimensionPolicy.SHARED_BUCKET,
                OptionalInt.empty());
        return new RateLimitEdgeConfig(true, List.of(globalRule), "/*");
    }

    private Future<Integer> deploy(Vertx vertx, RateLimitEdgeMiddleware middleware) {
        AtomicBoolean resourceInvoked = new AtomicBoolean(false);
        HttpVerticle verticle = new HttpVerticle(
                RateLimitEdgeTestFixture.localhostOptions(),
                Set.of(),
                Set.of(middleware),
                Set.of(RateLimitEdgeTestFixture.echoBodyMount(resourceInvoked)),
                Set.of());
        return RateLimitEdgeTestFixture.deploy(vertx, verticle);
    }

    private Future<io.vertx.ext.web.client.HttpResponse<Buffer>> post(Vertx vertx, int port) {
        client = WebClient.create(vertx);
        return client.post(port, "127.0.0.1", "/api/echo").sendBuffer(Buffer.buffer("payload"));
    }
}
