// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the log-safe rendering of {@link TokenAttributes}: the header in full, registered
 * non-sensitive claims with values, every other claim by name only — while the accessors keep
 * returning the hidden values.
 */
@DisplayName("TokenAttributes rendering")
class TokenAttributesRenderingTest {

    private static final String SUBJECT = "user-42";
    private static final String EMAIL = "alice@example.test";
    private static final String USERNAME = "alice";

    private static TokenAttributes sample() {
        return new TokenAttributes(
                Optional.of(Map.of("alg", "RS256", "kid", "k1", "typ", "JWT")),
                Optional.of(Map.of(
                        "iss",
                        "https://idp.example",
                        "aud",
                        "api",
                        "exp",
                        1_788_000_000L,
                        "sub",
                        SUBJECT,
                        "email",
                        EMAIL,
                        "tenant",
                        "acme")),
                Optional.of(Map.of("active", true, "client_id", "svc-1", "username", USERNAME)));
    }

    @Test
    @DisplayName("header renders in full and registered claims keep their values")
    void disclosedValuesRender() {
        String rendered = sample().toString();

        assertTrue(rendered.contains("jwtHeader={"), rendered);
        assertTrue(rendered.contains("alg=RS256") && rendered.contains("kid=k1") && rendered.contains("typ=JWT"));
        assertTrue(rendered.contains("iss=https://idp.example"));
        assertTrue(rendered.contains("aud=api"));
        assertTrue(rendered.contains("exp=1788000000"));
        assertTrue(rendered.contains("active=true"));
        assertTrue(rendered.contains("client_id=svc-1"));
    }

    @Test
    @DisplayName("subject, personal, and custom claims render by name only")
    void sensitiveValuesRedacted() {
        String rendered = sample().toString();

        assertTrue(rendered.contains("sub=<redacted>"), rendered);
        assertTrue(rendered.contains("email=<redacted>"), rendered);
        assertTrue(rendered.contains("tenant=<redacted>"), rendered);
        assertTrue(rendered.contains("username=<redacted>"), rendered);
        assertFalse(rendered.contains(SUBJECT), "subject value must not render");
        assertFalse(rendered.contains(EMAIL), "email value must not render");
        assertFalse(rendered.contains(USERNAME), "introspection username must not render");
    }

    @Test
    @DisplayName("names are sorted so two renderings of the same shape compare equal")
    void namesSorted() {
        String rendered = sample().toString();

        int aud = rendered.indexOf("aud=");
        int email = rendered.indexOf("email=");
        int exp = rendered.indexOf("exp=");
        int sub = rendered.indexOf("sub=");
        assertTrue(aud < email && email < exp && exp < sub, rendered);
    }

    @Test
    @DisplayName("absent parts render as absent")
    void absentParts() {
        TokenAttributes empty = new TokenAttributes(Optional.empty(), Optional.empty(), Optional.empty());

        assertEquals(
                "TokenAttributes[jwtHeader=absent, jwtClaims=absent, introspectionResponse=absent]", empty.toString());
    }

    @Test
    @DisplayName("every disclosed name renders, every lookalike is redacted, and the shape is exact")
    void allowListBoundary() {
        Map<String, Object> claims = Map.ofEntries(
                Map.entry("iss", "https://idp.example"),
                Map.entry("aud", List.of("api", "svc")),
                Map.entry("exp", 2L),
                Map.entry("nbf", 1L),
                Map.entry("iat", 1L),
                Map.entry("jti", "t-1"),
                Map.entry("azp", "c1"),
                Map.entry("typ", "at+jwt"),
                Map.entry("scope", "read tenant:acme"),
                Map.entry("scp", List.of("read")),
                Map.entry("client_id", "alice@example.test"),
                Map.entry("token_type", "Bearer"),
                Map.entry("active", true),
                Map.entry("Aud", "case-variant"),
                Map.entry("sub", SUBJECT),
                Map.entry("preferred_username", USERNAME),
                Map.entry("groups", List.of("admins")));

        String rendered = new TokenAttributes(Optional.empty(), Optional.of(claims), Optional.empty()).toString();

        assertEquals(
                "TokenAttributes[jwtHeader=absent, jwtClaims={Aud=<redacted>, active=true, aud=[api, svc], azp=c1,"
                        + " client_id=alice@example.test, exp=2, groups=<redacted>, iat=1, iss=https://idp.example,"
                        + " jti=t-1, nbf=1, preferred_username=<redacted>, scope=read tenant:acme, scp=[read],"
                        + " sub=<redacted>, token_type=Bearer, typ=at+jwt}, introspectionResponse=absent]",
                rendered);
    }

    @Test
    @DisplayName("a nested object under a disclosed name is redacted and control characters are neutralised")
    void nestedValuesAndControlCharacters() {
        Map<String, Object> claims = Map.of(
                "scope", Map.of("granted", List.of("read"), "on_behalf_of", EMAIL),
                "aud", List.of("api", Map.of("user", SUBJECT)),
                "iss", "idp\r\n2026-09-06 INFO forged");

        String rendered = new TokenAttributes(
                        Optional.of(Map.of("kid", "k1\nforged")), Optional.of(claims), Optional.empty())
                .toString();

        assertEquals(
                "TokenAttributes[jwtHeader={kid=k1?forged}, jwtClaims={aud=[api, <redacted>],"
                        + " iss=idp??2026-09-06 INFO forged, scope=<redacted>}, introspectionResponse=absent]",
                rendered);
        assertFalse(rendered.contains(EMAIL));
        assertFalse(rendered.contains(SUBJECT));
    }

    @Test
    @DisplayName("accessors still return the redacted values")
    void accessorsUnaffected() {
        TokenAttributes attributes = sample();

        assertEquals(SUBJECT, attributes.jwtClaims().orElseThrow().get("sub"));
        assertEquals(EMAIL, attributes.jwtClaims().orElseThrow().get("email"));
        assertEquals(USERNAME, attributes.introspectionResponse().orElseThrow().get("username"));
    }
}
