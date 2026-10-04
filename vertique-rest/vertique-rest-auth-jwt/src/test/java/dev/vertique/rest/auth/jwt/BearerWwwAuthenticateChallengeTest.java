// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.auth.jwt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link BearerWwwAuthenticateChallenge} challenge formatting and header application.
 */
class BearerWwwAuthenticateChallengeTest {

    @Test
    @DisplayName("Configured issuer becomes the Bearer realm")
    void challengeValue_usesIssuerAsRealm() {
        JwtValidationConfig config = JwtValidationConfig.builder()
                .issuer("https://auth.example.com/")
                .build();

        assertEquals(
                "Bearer realm=\"https://auth.example.com/\"", BearerWwwAuthenticateChallenge.challengeValue(config));
    }

    @Test
    @DisplayName("Missing issuer yields a bare Bearer challenge")
    void challengeValue_bareBearerWhenIssuerAbsent() {
        assertEquals(
                "Bearer",
                BearerWwwAuthenticateChallenge.challengeValue(
                        JwtValidationConfig.builder().build()));
    }

    @Test
    @DisplayName("Quotes in the issuer are escaped in the realm parameter")
    void challengeValue_escapesQuotesInRealm() {
        JwtValidationConfig config =
                JwtValidationConfig.builder().issuer("acme\"corp").build();

        assertEquals("Bearer realm=\"acme\\\"corp\"", BearerWwwAuthenticateChallenge.challengeValue(config));
    }

    @Test
    @DisplayName("Trailing backslash is escaped before the closing quote")
    void challengeValue_escapesTrailingBackslashInRealm() {
        JwtValidationConfig config =
                JwtValidationConfig.builder().issuer("acme\\").build();

        assertEquals("Bearer realm=\"acme\\\\\"", BearerWwwAuthenticateChallenge.challengeValue(config));
    }

    @Test
    @DisplayName("Backslash-plus-quote in the issuer is escaped as quoted-pairs")
    void challengeValue_escapesBackslashThenQuoteInRealm() {
        JwtValidationConfig config =
                JwtValidationConfig.builder().issuer("acme\\\"").build();

        assertEquals("Bearer realm=\"acme\\\\\\\"\"", BearerWwwAuthenticateChallenge.challengeValue(config));
    }

    @Test
    @DisplayName("CR/LF in the issuer drops the realm (bare Bearer)")
    void challengeValue_rejectsRealmWithControlChars() {
        JwtValidationConfig config =
                JwtValidationConfig.builder().issuer("evil\r\nInjected").build();

        assertEquals("Bearer", BearerWwwAuthenticateChallenge.challengeValue(config));
    }

    @Test
    @DisplayName("apply() appends WWW-Authenticate onto the response")
    void apply_appendsWwwAuthenticateHeader() {
        RoutingContext ctx = mock(RoutingContext.class);
        HttpServerResponse response = mock(HttpServerResponse.class);
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        when(ctx.response()).thenReturn(response);
        when(response.headers()).thenReturn(headers);

        JwtValidationConfig config =
                JwtValidationConfig.builder().issuer("https://issuer.test").build();

        assertEquals(true, BearerWwwAuthenticateChallenge.apply(ctx, config));
        assertEquals(
                List.of("Bearer realm=\"https://issuer.test\""), headers.getAll(BearerWwwAuthenticateChallenge.HEADER));
    }

    @Test
    @DisplayName("apply() preserves an existing non-Bearer challenge and deduplicates an identical Bearer")
    void apply_preservesOtherChallengesAndDedupsIdentical() {
        RoutingContext ctx = mock(RoutingContext.class);
        HttpServerResponse response = mock(HttpServerResponse.class);
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        headers.add(BearerWwwAuthenticateChallenge.HEADER, "Basic realm=\"x\"");
        when(ctx.response()).thenReturn(response);
        when(response.headers()).thenReturn(headers);

        JwtValidationConfig config =
                JwtValidationConfig.builder().issuer("https://issuer.test").build();
        String bearer = "Bearer realm=\"https://issuer.test\"";

        assertTrue(BearerWwwAuthenticateChallenge.apply(ctx, config));
        assertTrue(BearerWwwAuthenticateChallenge.apply(ctx, config));

        assertEquals(List.of("Basic realm=\"x\"", bearer), headers.getAll(BearerWwwAuthenticateChallenge.HEADER));
    }
}
