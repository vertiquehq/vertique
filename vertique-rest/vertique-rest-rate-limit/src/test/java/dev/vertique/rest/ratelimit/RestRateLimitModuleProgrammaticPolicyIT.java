// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vertique.ratelimit.RateLimitFailureMode;
import dev.vertique.ratelimit.RateLimiters;
import dev.vertique.rest.core.middleware.Middleware;
import dev.vertique.rest.core.router.HttpVerticle;
import dev.vertique.rest.security.RequestOriginConfig;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.List;
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
 * T010 follow-up: {@link RestRateLimitModule#rateLimitEdgeMiddlewareContribution} must classify an
 * {@code IP}-dimension rule's absent-origin decision correctly for a policy declared <strong>only</strong>
 * via a Dagger {@code @IntoSet RateLimitPolicy} contribution — one with no backing {@code
 * rateLimit.policies.<name>} JSON section anywhere. The module previously re-parsed {@code
 * rateLimit.policies.<name>.failureMode} straight out of the shared root config JSON, which threw
 * {@code ConfigurationException} for exactly this policy shape; it now reads {@link
 * dev.vertique.ratelimit.RateLimiter#failureMode()} off the already-resolved handle instead, so the
 * classification is correct regardless of whether the policy came from JSON, a programmatic {@code
 * @IntoSet} contribution, or {@code RateLimitCoreModule}'s config-wins merge of both.
 *
 * <p>Exercises the module's real (package-visible) static provider method directly — no Dagger
 * component needed, since a Dagger {@code @Provides} method is a plain static method — rather than
 * {@link RateLimitEdgeMiddleware}'s constructor directly, since the bug this guards against lived in
 * the module's old JSON re-parse workaround, not the middleware itself.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class RestRateLimitModuleProgrammaticPolicyIT {

    private WebClient client;

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    void shouldClassifyProgrammaticOnlyPolicyByItsRealFailureModeWhenOriginAbsent_closed(
            Vertx vertx, VertxTestContext ctx) {
        assertProgrammaticOnlyPolicyClassification(vertx, ctx, RateLimitFailureMode.CLOSED, 503);
    }

    @Test
    void shouldClassifyProgrammaticOnlyPolicyByItsRealFailureModeWhenOriginAbsent_open(
            Vertx vertx, VertxTestContext ctx) {
        assertProgrammaticOnlyPolicyClassification(vertx, ctx, RateLimitFailureMode.OPEN, 200);
    }

    private void assertProgrammaticOnlyPolicyClassification(
            Vertx vertx, VertxTestContext ctx, RateLimitFailureMode failureMode, int expectedStatus) {
        String policyName = "programmatic-only-" + failureMode.name().toLowerCase();
        // Built directly via RateLimiters's own constructor over a hand-built RateLimitPolicy — the
        // same shape RateLimitCoreModule's rateLimiters() provider produces for a Dagger @IntoSet
        // RateLimitPolicy contribution once merged. Critically, no rateLimit.policies.<policyName>
        // JSON section is constructed anywhere in this test: this policy exists ONLY inside
        // rateLimiters, exactly mirroring a programmatic-only application contribution.
        RateLimiters rateLimiters = RateLimitEdgeTestFixture.rateLimiters(
                vertx, RateLimitEdgeTestFixture.tokenBucketPolicy(policyName, 1L, failureMode));

        RateLimitEdgeRule ipRule = new RateLimitEdgeRule(
                policyName,
                List.of(RateLimitEdgeKeyDimension.IP),
                Optional.empty(),
                OptionalLong.empty(),
                MissingDimensionPolicy.SHARED_BUCKET,
                OptionalInt.of(RateLimitEdgeRule.DEFAULT_IPV6_PREFIX_BITS));
        RateLimitEdgeConfig config = new RateLimitEdgeConfig(true, List.of(ipRule), "/*");

        // A present binding simulates AuthModule/OriginCaptureMiddleware being co-installed, so the
        // IP-dimension startup validation passes; OriginCaptureMiddleware itself is deliberately
        // NOT deployed below, so RequestOrigin is absent from the routing context at request time —
        // forcing the middleware down its failureMode-classification branch.
        Optional<RequestOriginConfig> originCaptureBinding =
                Optional.of(new RequestOriginConfig(Set.of(), 16, false, false));

        Middleware edge =
                RestRateLimitModule.rateLimitEdgeMiddlewareContribution(config, rateLimiters, originCaptureBinding);

        AtomicBoolean invoked = new AtomicBoolean(false);
        HttpVerticle verticle = new HttpVerticle(
                RateLimitEdgeTestFixture.localhostOptions(),
                Set.of(),
                Set.of(edge),
                Set.of(RateLimitEdgeTestFixture.echoBodyMount(invoked)),
                Set.of());

        RateLimitEdgeTestFixture.deploy(vertx, verticle).onComplete(ctx.succeeding(port -> {
            client = WebClient.create(vertx);
            client.post(port, "127.0.0.1", "/api/echo")
                    .sendBuffer(Buffer.buffer("payload"))
                    .onComplete(ctx.succeeding(response -> {
                        assertEquals(
                                expectedStatus,
                                response.statusCode(),
                                "a policy contributed only programmatically (no rateLimit.policies JSON"
                                        + " section) must still classify per its real failureMode via"
                                        + " RateLimiter#failureMode(), not a JSON re-parse");
                        ctx.completeNow();
                    }));
        }));
    }
}
