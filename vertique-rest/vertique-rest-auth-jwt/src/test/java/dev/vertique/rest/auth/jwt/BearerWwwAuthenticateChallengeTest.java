// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.auth.jwt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
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
    @DisplayName("CR/LF in the issuer drops the realm (bare Bearer)")
    void challengeValue_rejectsRealmWithControlChars() {
        JwtValidationConfig config =
                JwtValidationConfig.builder().issuer("evil\r\nInjected").build();

        assertEquals("Bearer", BearerWwwAuthenticateChallenge.challengeValue(config));
    }

    @Test
    @DisplayName("apply() writes WWW-Authenticate onto the response")
    void apply_setsWwwAuthenticateHeader() {
        RoutingContext ctx = mock(RoutingContext.class);
        HttpServerResponse response = mock(HttpServerResponse.class);
        when(ctx.response()).thenReturn(response);
        when(response.putHeader(any(CharSequence.class), any(CharSequence.class)))
                .thenReturn(response);

        JwtValidationConfig config =
                JwtValidationConfig.builder().issuer("https://issuer.test").build();

        assertEquals(true, BearerWwwAuthenticateChallenge.apply(ctx, config));
        verify(response).putHeader(BearerWwwAuthenticateChallenge.HEADER, "Bearer realm=\"https://issuer.test\"");
    }
}
