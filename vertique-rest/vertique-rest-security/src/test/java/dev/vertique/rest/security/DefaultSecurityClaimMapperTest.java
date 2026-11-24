// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vertique.security.authz.AuthorityClaim;
import dev.vertique.security.authz.AuthorityKind;
import dev.vertique.security.authz.AuthorizationClaims;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Pins how {@link DefaultSecurityClaimMapper} reads each claim value type, and that it reads every
 * value exactly as {@link JwtClaimExtractor} does, the parser the JWT claim authorization provider
 * uses.
 */
class DefaultSecurityClaimMapperTest {

    private final DefaultSecurityClaimMapper mapper = new DefaultSecurityClaimMapper();

    @Test
    @DisplayName("Maps roles, scope, scp and permissions to their authority kinds")
    void mapsEachClaimToItsKind() {
        Map<String, Object> claims = new HashMap<>();
        claims.put("roles", new JsonArray().add("admin"));
        claims.put("scope", "read write");
        claims.put("scp", List.of("orders"));
        claims.put("permissions", "delete");

        AuthorizationClaims mapped = mapper.map(claims);

        assertEquals(
                Set.of(
                        claim(AuthorityKind.ROLE, "admin"),
                        claim(AuthorityKind.SCOPE, "read"),
                        claim(AuthorityKind.SCOPE, "write"),
                        claim(AuthorityKind.SCOPE, "orders"),
                        claim(AuthorityKind.PERMISSION, "delete")),
                mapped.claims());
        assertEquals(Map.of(), mapped.attributes());
    }

    @Test
    @DisplayName("Missing claims map to no authority claims")
    void missingClaimsMapToNothing() {
        assertEquals(Set.of(), mapper.map(Map.of()).claims());
    }

    static List<Arguments> claimValues() {
        Map<String, Object> rawMap = new HashMap<>();
        rawMap.put("x admin y", "v");
        List<Object> mixedList = new ArrayList<>();
        mixedList.add("admin");
        mixedList.add(7);
        mixedList.add(null);
        mixedList.add(" ");
        return List.of(
                Arguments.of(
                        "JSON array of strings", new JsonArray().add("admin").add("user"), Set.of("admin", "user")),
                Arguments.of("JSON array with non-strings and blanks", new JsonArray(mixedList), Set.of("admin")),
                Arguments.of("java.util.List", mixedList, Set.of("admin")),
                Arguments.of(
                        "java.util.List with an enum element", List.of("admin", AuthorityKind.ROLE), Set.of("admin")),
                Arguments.of("space-delimited string", " admin\tuser ", Set.of("admin", "user")),
                Arguments.of("blank string", "   ", Set.of()),
                Arguments.of("set", new LinkedHashSet<>(List.of("x", "y")), Set.of("x", "y")),
                Arguments.of("non-String CharSequence", new StringBuilder("admin user"), Set.of()),
                Arguments.of("number", 42, Set.of()),
                Arguments.of("boolean", true, Set.of()),
                Arguments.of("enum", AuthorityKind.ROLE, Set.of()),
                Arguments.of("JSON object holding a role name", new JsonObject().put("k", "x admin y"), Set.of()),
                Arguments.of("raw map", rawMap, Set.of()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("claimValues")
    @DisplayName("Reads each value type the way the shared extractor does")
    void readsEachValueTypeLikeTheExtractor(String label, Object value, Set<String> expected) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("roles", value);
        claims.put("scope", value);
        claims.put("permissions", value);

        AuthorizationClaims mapped = mapper.map(claims);

        assertEquals(expected, values(mapped, AuthorityKind.ROLE), label + " (roles)");
        assertEquals(expected, values(mapped, AuthorityKind.SCOPE), label + " (scope)");
        assertEquals(expected, values(mapped, AuthorityKind.PERMISSION), label + " (permissions)");

        JsonObject principal = new JsonObject(claims);
        assertEquals(JwtClaimExtractor.extractRoles(principal), values(mapped, AuthorityKind.ROLE));
        assertEquals(JwtClaimExtractor.extractScopes(principal), values(mapped, AuthorityKind.SCOPE));
        assertEquals(JwtClaimExtractor.extractPermissions(principal), values(mapped, AuthorityKind.PERMISSION));
    }

    private static AuthorityClaim claim(AuthorityKind kind, String value) {
        return new AuthorityClaim(kind, value, "", "", "", Map.of());
    }

    private static Set<String> values(AuthorizationClaims claims, AuthorityKind kind) {
        return claims.claims().stream()
                .filter(claim -> claim.kind() == kind)
                .map(AuthorityClaim::value)
                .collect(Collectors.toSet());
    }
}
