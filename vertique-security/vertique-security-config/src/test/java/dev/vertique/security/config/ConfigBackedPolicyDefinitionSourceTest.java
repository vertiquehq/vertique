// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.vertique.security.authz.ActionDefinition;
import dev.vertique.security.authz.ActionRef;
import dev.vertique.security.authz.ActionRegistry;
import dev.vertique.security.authz.Effect;
import dev.vertique.security.authz.PolicyDefinition;
import dev.vertique.security.authz.PolicyStatement;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ConfigBackedPolicyDefinitionSource}.
 *
 * <p>Verifies that the config-backed {@link dev.vertique.security.authz.PolicyDefinitionSource}
 * parses policies from {@link AuthorizationConfig} into {@link PolicyDefinition} objects, and fails
 * at startup via {@code withRegistry()} when a policy's action pattern references an unregistered
 * action.
 */
class ConfigBackedPolicyDefinitionSourceTest {

    private static final ActionRef CMS_CONTENT_READ = ActionRef.of("cms", "content", "read");

    // --- parsing ---

    @Test
    @DisplayName("policies() returns PolicyDefinitions parsed from config with correct name, effect, and pattern")
    void policies_fromConfig_parsedCorrectly() {
        PolicyDefinitionConfig policyConfig = new PolicyDefinitionConfig(
                "test-policy", List.of(new PolicyStatementConfig(Effect.ALLOW, List.of("cms.content.read"))));
        AuthorizationConfig config = new AuthorizationConfig(Map.of(), List.of(policyConfig));
        ConfigBackedPolicyDefinitionSource source = new ConfigBackedPolicyDefinitionSource(config);

        Collection<PolicyDefinition> policies = source.policies();
        assertEquals(1, policies.size());
        PolicyDefinition policy = policies.iterator().next();
        assertEquals("test-policy", policy.name());
        assertEquals(1, policy.statements().size());
        PolicyStatement statement = policy.statements().get(0);
        assertEquals(Effect.ALLOW, statement.effect());
        assertEquals(1, statement.actions().size());
        assertEquals("cms.content.read", statement.actions().iterator().next().value());
    }

    // --- startup validation ---

    @Test
    @DisplayName("withRegistry() throws when config policy references an action not in the registry")
    void startup_unknownAction_inConfig_throws() {
        PolicyDefinitionConfig policyConfig = new PolicyDefinitionConfig(
                "bad-policy", List.of(new PolicyStatementConfig(Effect.ALLOW, List.of("cms.unknown.read"))));
        AuthorizationConfig config = new AuthorizationConfig(Map.of(), List.of(policyConfig));
        ConfigBackedPolicyDefinitionSource source = new ConfigBackedPolicyDefinitionSource(config);

        ActionRegistry registry = new FakeActionRegistry(Set.of(CMS_CONTENT_READ));
        assertThrows(IllegalStateException.class, () -> source.withRegistry(registry));
    }

    @Test
    @DisplayName("validateAgainst() (the polymorphic SPI hook) throws on an unregistered action")
    void validateAgainst_unknownAction_throws() {
        PolicyDefinitionConfig policyConfig = new PolicyDefinitionConfig(
                "bad-policy", List.of(new PolicyStatementConfig(Effect.ALLOW, List.of("cms.unknown.read"))));
        AuthorizationConfig config = new AuthorizationConfig(Map.of(), List.of(policyConfig));
        ConfigBackedPolicyDefinitionSource source = new ConfigBackedPolicyDefinitionSource(config);

        ActionRegistry registry = new FakeActionRegistry(Set.of(CMS_CONTENT_READ));
        // Exercised through the PolicyDefinitionSource SPI type, as the core wiring invokes it.
        dev.vertique.security.authz.PolicyDefinitionSource spi = source;
        assertThrows(IllegalStateException.class, () -> spi.validateAgainst(registry));
    }

    // --- helpers ---

    /** Fake {@link ActionRegistry} backed by a fixed set of {@link ActionRef}s. */
    private static final class FakeActionRegistry implements ActionRegistry {
        private final Map<String, ActionDefinition> byValue = new LinkedHashMap<>();

        FakeActionRegistry(Set<ActionRef> refs) {
            for (ActionRef ref : refs) {
                byValue.put(ref.value(), new ActionDefinition(ref));
            }
        }

        @Override
        public Collection<ActionDefinition> actions() {
            return byValue.values();
        }

        @Override
        public Optional<ActionDefinition> find(ActionRef action) {
            return Optional.ofNullable(
                    byValue.get(Objects.requireNonNull(action).value()));
        }

        @Override
        public boolean contains(ActionRef action) {
            return byValue.containsKey(Objects.requireNonNull(action).value());
        }
    }
}
