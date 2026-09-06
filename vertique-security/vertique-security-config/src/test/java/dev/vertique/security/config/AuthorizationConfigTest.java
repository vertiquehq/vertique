// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.security.authz.Effect;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the three behaviors of the configuration records that the wired-path tests do not assert
 * directly: the omitted {@code effect} default, the absent-section default, and the deep copy.
 */
@DisplayName("Authorization configuration records")
class AuthorizationConfigTest {

    @Test
    @DisplayName("a statement without effect defaults to ALLOW")
    void omittedEffect_defaultsToAllow() throws Exception {
        String json = """
                { "policies": [ { "name": "p", "statements": [ { "actions": [ "cms.content.read" ] } ] } ] }
                """;

        AuthorizationConfig config = DefaultConfigMapper.lenient().readValue(json, AuthorizationConfig.class);

        PolicyStatementConfig statement = config.policies().get(0).statements().get(0);
        assertEquals(Effect.ALLOW, statement.effect());
        assertEquals(List.of("cms.content.read"), statement.actions());
    }

    @Test
    @DisplayName("an absent authorization section yields the defaults")
    void absentSection_yieldsDefaults() throws Exception {
        AuthorizationConfig fromEmptyObject = DefaultConfigMapper.lenient().readValue("{}", AuthorizationConfig.class);

        assertEquals(AuthorizationConfig.defaults(), fromEmptyObject);
        assertEquals(AuthorizationConfig.defaults(), AuthorizationConfig.fromJson(null, null));
        assertEquals(Map.of(), AuthorizationConfig.defaults().rolePolicies());
        assertEquals(List.of(), AuthorizationConfig.defaults().policies());
    }

    @Test
    @DisplayName("role mappings are deep-copied at construction")
    void rolePolicies_deepCopied() {
        List<String> adminPolicies = new ArrayList<>(List.of("admin-policy"));
        Map<String, List<String>> rolePolicies = new HashMap<>();
        rolePolicies.put("admin", adminPolicies);

        AuthorizationConfig config = new AuthorizationConfig(rolePolicies, List.of());
        adminPolicies.add("smuggled");
        rolePolicies.put("viewer", List.of("viewer-policy"));

        assertEquals(Map.of("admin", List.of("admin-policy")), config.rolePolicies());
    }
}
