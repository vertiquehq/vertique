// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.ratelimit.RateLimitAlgorithmType;
import dev.vertique.ratelimit.RateLimitDecision;
import dev.vertique.ratelimit.RateLimitKey;
import dev.vertique.ratelimit.RateLimitMode;
import dev.vertique.ratelimit.RateLimitOutcome;
import dev.vertique.ratelimit.RateLimiter;
import dev.vertique.ratelimit.RateLimiters;
import dev.vertique.security.origin.RequestOrigin;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * TP-003: two ordered rules — a permissive {@code IP} rule then a denying {@code HEADER} rule —
 * against a fake {@link RateLimiters} that records every {@code acquire(...)} call
 * (contracts/rest-adapter.md, "Rule composition semantics").
 */
class RateLimitEdgeRuleCompositionTest {

    private static final String IP_POLICY = "edge-ip";
    private static final String HEADER_POLICY = "edge-apikey";
    private static final String HEADER_NAME = "X-Api-Key";

    @Test
    void shouldRejectGlobalCombinedWithAnyOtherDimensionAtStartup() {
        assertThatThrownBy(() -> new RateLimitEdgeRule(
                        "edge-global",
                        List.of(RateLimitEdgeKeyDimension.GLOBAL, RateLimitEdgeKeyDimension.IP),
                        Optional.empty(),
                        OptionalLong.empty(),
                        MissingDimensionPolicy.SHARED_BUCKET,
                        OptionalInt.empty()))
                .isInstanceOf(ConfigurationException.class);
    }

    @Test
    void shouldRequireHeaderNameWhenKeyContainsHeader() {
        assertThatThrownBy(() -> new RateLimitEdgeRule(
                        HEADER_POLICY,
                        List.of(RateLimitEdgeKeyDimension.HEADER),
                        Optional.empty(),
                        OptionalLong.empty(),
                        MissingDimensionPolicy.SHARED_BUCKET,
                        OptionalInt.empty()))
                .isInstanceOf(ConfigurationException.class);
    }

    @Test
    void shouldStopAtFirstNonPermittingRule() {
        Fixture fx = Fixture.ipThenHeader(MissingDimensionPolicy.SHARED_BUCKET);
        RoutingContext ctx = fx.contextWithHeader(HEADER_NAME, "caller-key-1");

        fx.middleware.handle(ctx);

        verify(fx.ipLimiter, times(1)).acquire(any(RateLimitKey.class));
        verify(fx.headerLimiter, times(1)).acquire(any(RateLimitKey.class));
        verify(ctx, never()).next();
        verify(fx.response).setStatusCode(429);
    }

    @Test
    void shouldKeepEarlierConsumedTokensConsumedOnLaterRejection() {
        Fixture fx = Fixture.ipThenHeader(MissingDimensionPolicy.SHARED_BUCKET);
        RoutingContext ctx = fx.contextWithHeader(HEADER_NAME, "caller-key-1");

        fx.middleware.handle(ctx);

        // RateLimiter exposes no compensating release/refund call at all, so the IP rule's single
        // recorded acquire() can never be undone by this middleware; the exact recorded
        // interaction count below is the proof — no "undo" call exists to make or verify absent.
        verify(fx.ipLimiter, times(1)).acquire(any(RateLimitKey.class));
    }

    @Test
    void shouldReflectDeclaredOrderInAcquireSequenceAndWinningRule() {
        Fixture ipFirst = Fixture.ipThenHeader(MissingDimensionPolicy.SHARED_BUCKET);
        ipFirst.middleware.handle(ipFirst.contextWithHeader(HEADER_NAME, "caller-key-1"));
        assertThat(ipFirst.callOrder).containsExactly("ip", "header");

        // Sensitivity proof: swap declared order — header now runs first and denies immediately,
        // so ip is never reached at all. The recorded sequence changes to match the new order,
        // not merely to "whichever rule happens to deny".
        Fixture headerFirst = Fixture.headerThenIp(MissingDimensionPolicy.SHARED_BUCKET);
        headerFirst.middleware.handle(headerFirst.contextWithHeader(HEADER_NAME, "caller-key-1"));
        assertThat(headerFirst.callOrder).containsExactly("header");
        verify(headerFirst.ipLimiter, never()).acquire(any(RateLimitKey.class));
    }

    @Test
    void shouldShareOneBucketAcrossMissingHeaderCallersUnderSharedBucketMissingDimension() {
        Fixture fx = Fixture.ipThenHeader(MissingDimensionPolicy.SHARED_BUCKET);
        ArgumentCaptor<RateLimitKey> captor = ArgumentCaptor.forClass(RateLimitKey.class);

        fx.middleware.handle(fx.contextWithNoHeader());
        fx.middleware.handle(fx.contextWithNoHeader());

        verify(fx.headerLimiter, times(2)).acquire(captor.capture());
        List<RateLimitKey> keys = captor.getAllValues();
        assertThat(keys.get(0)).isEqualTo(keys.get(1));
    }

    @Test
    void shouldBypassTheRuleForMissingHeaderUnderBypassMissingDimension() {
        Fixture fx = Fixture.ipThenHeader(MissingDimensionPolicy.BYPASS);
        RoutingContext ctx = fx.contextWithNoHeader();

        fx.middleware.handle(ctx);

        verify(fx.headerLimiter, never()).acquire(any(RateLimitKey.class));
        verify(fx.ipLimiter, times(1)).acquire(any(RateLimitKey.class));
        verify(ctx).next();
    }

    @Test
    void shouldTreatARepeatedHeaderAsMissingNeverKeyedOnFirstOccurrence() {
        Fixture fx = Fixture.ipThenHeader(MissingDimensionPolicy.SHARED_BUCKET);
        ArgumentCaptor<RateLimitKey> captor = ArgumentCaptor.forClass(RateLimitKey.class);

        fx.middleware.handle(fx.contextWithNoHeader());
        fx.middleware.handle(fx.contextWithHeaders(HEADER_NAME, "caller-key-1", "caller-key-2"));

        verify(fx.headerLimiter, times(2)).acquire(captor.capture());
        RateLimitKey missingKey = captor.getAllValues().get(0);
        RateLimitKey repeatedKey = captor.getAllValues().get(1);
        assertThat(repeatedKey).isEqualTo(missingKey);

        // Sensitivity proof: a genuinely single-valued request with the repeated row's first
        // value must key differently from both the missing-header marker and the repeated-header
        // key — proving this assertion is sensitive to actual header-count, not a hardcoded key.
        Fixture single = Fixture.ipThenHeader(MissingDimensionPolicy.SHARED_BUCKET);
        ArgumentCaptor<RateLimitKey> singleCaptor = ArgumentCaptor.forClass(RateLimitKey.class);
        single.middleware.handle(single.contextWithHeader(HEADER_NAME, "caller-key-1"));
        verify(single.headerLimiter).acquire(singleCaptor.capture());
        RateLimitKey singleKey = singleCaptor.getValue();

        assertThat(singleKey).isNotEqualTo(missingKey);
        assertThat(singleKey).isNotEqualTo(repeatedKey);
    }

    private static RateLimitDecision permitDecision(String policyName) {
        return new RateLimitDecision(
                policyName,
                RateLimitOutcome.PERMITTED,
                RateLimitMode.LOCAL,
                RateLimitAlgorithmType.TOKEN_BUCKET,
                100L,
                OptionalLong.of(99L),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private static RateLimitDecision quotaExceededDecision(String policyName) {
        return new RateLimitDecision(
                policyName,
                RateLimitOutcome.QUOTA_EXCEEDED,
                RateLimitMode.LOCAL,
                RateLimitAlgorithmType.TOKEN_BUCKET,
                1L,
                OptionalLong.of(0L),
                Optional.of(Duration.ofSeconds(1)),
                Optional.empty(),
                Optional.empty());
    }

    /** Intent-revealing fixture hiding the fake {@link RateLimiters}/mocked-context harness. */
    private static final class Fixture {
        private static final RequestOrigin ORIGIN = new RequestOrigin(
                "198.51.100.7", 54321, List.of(), 0, false, "198.51.100.7", "https", "example.com", Optional.empty());

        final RateLimiter ipLimiter = mock(RateLimiter.class);
        final RateLimiter headerLimiter = mock(RateLimiter.class);
        final HttpServerResponse response = mock(HttpServerResponse.class);
        final List<String> callOrder = new ArrayList<>();
        final RateLimitEdgeMiddleware middleware;

        private Fixture(boolean ipFirst, MissingDimensionPolicy missingDimension) {
            when(ipLimiter.acquire(any(RateLimitKey.class))).thenAnswer(invocation -> {
                callOrder.add("ip");
                return Future.succeededFuture(permitDecision(IP_POLICY));
            });
            when(headerLimiter.acquire(any(RateLimitKey.class))).thenAnswer(invocation -> {
                callOrder.add("header");
                return Future.succeededFuture(quotaExceededDecision(HEADER_POLICY));
            });

            RateLimiters rateLimiters = mock(RateLimiters.class);
            when(rateLimiters.limiter(IP_POLICY)).thenReturn(ipLimiter);
            when(rateLimiters.limiter(HEADER_POLICY)).thenReturn(headerLimiter);

            RateLimitEdgeRule ipRule = new RateLimitEdgeRule(
                    IP_POLICY,
                    List.of(RateLimitEdgeKeyDimension.IP),
                    Optional.empty(),
                    OptionalLong.empty(),
                    MissingDimensionPolicy.SHARED_BUCKET,
                    OptionalInt.empty());
            RateLimitEdgeRule headerRule = new RateLimitEdgeRule(
                    HEADER_POLICY,
                    List.of(RateLimitEdgeKeyDimension.HEADER),
                    Optional.of(HEADER_NAME),
                    OptionalLong.empty(),
                    missingDimension,
                    OptionalInt.empty());
            List<RateLimitEdgeRule> rules = ipFirst ? List.of(ipRule, headerRule) : List.of(headerRule, ipRule);
            RateLimitEdgeConfig config = new RateLimitEdgeConfig(true, rules, "/*");

            this.middleware = new RateLimitEdgeMiddleware(config, rateLimiters, true);

            when(response.putHeader(anyString(), anyString())).thenReturn(response);
            when(response.setStatusCode(anyInt())).thenReturn(response);
            when(response.end(anyString())).thenReturn(Future.succeededFuture());
        }

        static Fixture ipThenHeader(MissingDimensionPolicy missingDimension) {
            return new Fixture(true, missingDimension);
        }

        static Fixture headerThenIp(MissingDimensionPolicy missingDimension) {
            return new Fixture(false, missingDimension);
        }

        RoutingContext contextWithHeader(String name, String value) {
            MultiMap headers = MultiMap.caseInsensitiveMultiMap();
            headers.add(name, value);
            return newContext(headers);
        }

        RoutingContext contextWithHeaders(String name, String... values) {
            MultiMap headers = MultiMap.caseInsensitiveMultiMap();
            for (String value : values) {
                headers.add(name, value);
            }
            return newContext(headers);
        }

        RoutingContext contextWithNoHeader() {
            return newContext(MultiMap.caseInsensitiveMultiMap());
        }

        private RoutingContext newContext(MultiMap headers) {
            RoutingContext ctx = mock(RoutingContext.class);
            when(ctx.get(RequestOrigin.class.getName())).thenReturn(ORIGIN);
            HttpServerRequest request = mock(HttpServerRequest.class);
            when(request.headers()).thenReturn(headers);
            when(ctx.request()).thenReturn(request);
            when(ctx.response()).thenReturn(response);
            return ctx;
        }
    }
}
