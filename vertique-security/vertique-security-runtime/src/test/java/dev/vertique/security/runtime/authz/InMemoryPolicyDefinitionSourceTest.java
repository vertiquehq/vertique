// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime.authz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.security.authz.ActionDefinition;
import dev.vertique.security.authz.ActionPattern;
import dev.vertique.security.authz.ActionRef;
import dev.vertique.security.authz.ActionRegistry;
import dev.vertique.security.authz.Effect;
import dev.vertique.security.authz.PolicyDefinition;
import dev.vertique.security.authz.PolicyDefinitionSource;
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
 * Unit tests for {@link InMemoryPolicyDefinitionSource}.
 *
 * <p>Verifies the in-memory, programmatic default {@link PolicyDefinitionSource}: it exposes the
 * policies it was constructed with, rejects a policy with a blank name at construction, and — via the
 * {@code withRegistry(ActionRegistry)} startup-validation step — fails fast when a policy references
 * an exact action that is not registered or a wildcard that matches no registered action. A fake
 * {@link ActionRegistry} backed by a fixed action set stands in for the real registry.
 */
class InMemoryPolicyDefinitionSourceTest {

    private static final ActionRef CMS_CONTENT_READ = ActionRef.of("cms", "content", "read");
    private static final ActionRef CMS_CONTENT_WRITE = ActionRef.of("cms", "content", "write");

    @Test
    @DisplayName("policies() returns all registered policies")
    void policies_returnsAllRegistered() {
        PolicyDefinition first = allowPolicy("first", "cms.content.read");
        PolicyDefinition second = allowPolicy("second", "cms.content.write");
        InMemoryPolicyDefinitionSource source = new InMemoryPolicyDefinitionSource(List.of(first, second));
        Collection<PolicyDefinition> policies = source.policies();
        assertEquals(2, policies.size());
        assertTrue(policies.contains(first));
        assertTrue(policies.contains(second));
    }

    @Test
    @DisplayName("validation rejects a wildcard pattern that matches no registered action")
    void startup_wildcardMismatch_throws() {
        // registry has only authz.* actions; the policy's cms.content.* matches nothing
        ActionRegistry registry = new FakeActionRegistry(Set.of(ActionRef.of("authz", "action", "list")));
        InMemoryPolicyDefinitionSource source =
                new InMemoryPolicyDefinitionSource(List.of(allowPolicy("p", "cms.content.*")));
        assertThrows(IllegalStateException.class, () -> source.withRegistry(registry));
    }

    @Test
    @DisplayName("validation rejects an exact action that is not registered")
    void startup_unknownExactAction_throws() {
        ActionRegistry registry = new FakeActionRegistry(Set.of(CMS_CONTENT_READ));
        InMemoryPolicyDefinitionSource source =
                new InMemoryPolicyDefinitionSource(List.of(allowPolicy("p", "cms.unknown.read")));
        assertThrows(IllegalStateException.class, () -> source.withRegistry(registry));
    }

    @Test
    @DisplayName("validation accepts a policy whose exact and wildcard patterns are all satisfied")
    void startup_allActionsPresent_passes() {
        ActionRegistry registry = new FakeActionRegistry(Set.of(CMS_CONTENT_READ, CMS_CONTENT_WRITE));
        PolicyDefinition exact = allowPolicy("exact", "cms.content.read");
        PolicyDefinition wildcard = allowPolicy("wildcard", "cms.content.*");
        InMemoryPolicyDefinitionSource source = new InMemoryPolicyDefinitionSource(List.of(exact, wildcard));
        // withRegistry returns the validated source and must not throw
        assertEquals(source, source.withRegistry(registry));
    }

    @Test
    @DisplayName("a policy with a blank name is rejected at construction")
    void startup_emptyName_throws() {
        assertThrows(
                RuntimeException.class,
                () -> new InMemoryPolicyDefinitionSource(List.of(new PolicyDefinition("", List.of()))));
    }

    // --- helpers ---

    private static PolicyDefinition allowPolicy(String name, String pattern) {
        return new PolicyDefinition(
                name, List.of(new PolicyStatement(Effect.ALLOW, Set.of(new ActionPattern(pattern)))));
    }

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
