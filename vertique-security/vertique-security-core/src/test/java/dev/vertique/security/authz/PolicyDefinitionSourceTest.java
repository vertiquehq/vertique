// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.authz;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
 * Unit tests for the {@link PolicyDefinitionSource#validateAgainst(ActionRegistry)} <em>default</em>
 * method.
 *
 * <p>These tests deliberately exercise a source that does <strong>not</strong> override
 * {@code validateAgainst} (a lambda {@link PolicyDefinitionSource}, the shape a third-party or
 * programmatic contributor typically takes). They prove the SPI's fail-fast registry check runs for
 * every source by default — a non-overriding source referencing an unregistered exact action or a
 * wildcard that matches nothing fails fast, a source whose patterns are all satisfied passes, and a
 * source with no policies validates as a natural no-op. A fake {@link ActionRegistry} backed by a
 * fixed action set stands in for the real registry.
 */
class PolicyDefinitionSourceTest {

    private static final ActionRef CMS_CONTENT_READ = ActionRef.of("cms", "content", "read");
    private static final ActionRef CMS_CONTENT_WRITE = ActionRef.of("cms", "content", "write");

    @Test
    @DisplayName("non-overriding source: unregistered exact action fails the default validateAgainst")
    void defaultValidate_nonOverridingSource_unknownExactAction_throws() {
        ActionRegistry registry = new FakeActionRegistry(Set.of(CMS_CONTENT_READ));
        // A lambda PolicyDefinitionSource does not override validateAgainst — it must still be checked.
        PolicyDefinitionSource source = lambdaSource(allowPolicy("p", "cms.unknown.read"));
        assertThrows(IllegalStateException.class, () -> source.validateAgainst(registry));
    }

    @Test
    @DisplayName("non-overriding source: wildcard matching no registered action fails the default validateAgainst")
    void defaultValidate_nonOverridingSource_wildcardMismatch_throws() {
        // registry has only authz.* actions; the policy's cms.content.* matches nothing
        ActionRegistry registry = new FakeActionRegistry(Set.of(ActionRef.of("authz", "action", "list")));
        PolicyDefinitionSource source = lambdaSource(allowPolicy("p", "cms.content.*"));
        assertThrows(IllegalStateException.class, () -> source.validateAgainst(registry));
    }

    @Test
    @DisplayName("non-overriding source: all patterns satisfied passes the default validateAgainst")
    void defaultValidate_nonOverridingSource_allActionsPresent_passes() {
        ActionRegistry registry = new FakeActionRegistry(Set.of(CMS_CONTENT_READ, CMS_CONTENT_WRITE));
        PolicyDefinitionSource source =
                lambdaSource(allowPolicy("exact", "cms.content.read"), allowPolicy("wildcard", "cms.content.*"));
        assertDoesNotThrow(() -> source.validateAgainst(registry));
    }

    @Test
    @DisplayName("non-overriding source with no policies validates as a no-op")
    void defaultValidate_emptySource_isNoOp() {
        ActionRegistry registry = new FakeActionRegistry(Set.of(CMS_CONTENT_READ));
        List<PolicyDefinition> none = List.of();
        PolicyDefinitionSource source = () -> none;
        assertDoesNotThrow(() -> source.validateAgainst(registry));
    }

    // --- helpers ---

    /** Builds a lambda {@link PolicyDefinitionSource} (no {@code validateAgainst} override) over the given policies. */
    private static PolicyDefinitionSource lambdaSource(PolicyDefinition... policies) {
        List<PolicyDefinition> list = List.of(policies);
        return () -> list;
    }

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
