// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime.authz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.security.authz.ActionContributor;
import dev.vertique.security.authz.ActionDefinition;
import dev.vertique.security.authz.ActionRef;
import dev.vertique.security.authz.ActionRegistry;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DefaultActionRegistry}.
 *
 * <p>Verifies aggregation of contributed {@link ActionDefinition}s, lookup
 * ({@link ActionRegistry#find(ActionRef)} / {@link ActionRegistry#contains(ActionRef)}), and the
 * fail-fast startup validation: duplicate actions (keyed on {@link ActionRef#value()}) are rejected
 * with a message naming both contributing sources, and any contributor whose definitions are
 * {@code null} or violate the action grammar fails construction. Fakes implement
 * {@link ActionContributor} directly.
 */
class DefaultActionRegistryTest {

    private static final ActionRef CMS_CONTENT_READ = ActionRef.of("cms", "content", "read");

    // --- aggregation ---

    @Test
    @DisplayName("actions() returns all definitions contributed by all contributors")
    void actions_returnsAllContributed() {
        ActionDefinition a = new ActionDefinition(ActionRef.of("cms", "content", "read"));
        ActionDefinition b = new ActionDefinition(ActionRef.of("cms", "content", "write"));
        DefaultActionRegistry registry =
                new DefaultActionRegistry(Set.of(new FixedContributor(a), new FixedContributor(b)));
        Collection<ActionDefinition> actions = registry.actions();
        assertEquals(2, actions.size());
        assertTrue(actions.contains(a));
        assertTrue(actions.contains(b));
    }

    // --- find() / contains() ---

    @Test
    @DisplayName("find() returns the definition for a known action")
    void find_knownAction_present() {
        DefaultActionRegistry registry = registryWith(CMS_CONTENT_READ);
        assertTrue(registry.find(CMS_CONTENT_READ).isPresent());
        assertEquals(CMS_CONTENT_READ, registry.find(CMS_CONTENT_READ).get().ref());
    }

    @Test
    @DisplayName("find() returns empty for an unknown action")
    void find_unknownAction_empty() {
        DefaultActionRegistry registry = registryWith(CMS_CONTENT_READ);
        assertTrue(registry.find(ActionRef.of("x", "y", "z")).isEmpty());
    }

    @Test
    @DisplayName("contains() is true for a known action")
    void contains_knownAction_true() {
        DefaultActionRegistry registry = registryWith(CMS_CONTENT_READ);
        assertTrue(registry.contains(CMS_CONTENT_READ));
    }

    @Test
    @DisplayName("contains() is false for an unknown action")
    void contains_unknownAction_false() {
        DefaultActionRegistry registry = registryWith(CMS_CONTENT_READ);
        assertFalse(registry.contains(ActionRef.of("x", "y", "z")));
    }

    // --- startup validation ---

    @Test
    @DisplayName("duplicate action across two contributors throws naming both sources")
    void startup_duplicateAction_throws() {
        ActionContributor first = new FirstContributor();
        ActionContributor second = new SecondContributor();
        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> new DefaultActionRegistry(Set.of(first, second)));
        String message = ex.getMessage();
        assertTrue(
                message.contains(FirstContributor.class.getName()),
                "message should name first contributor but was: " + message);
        assertTrue(
                message.contains(SecondContributor.class.getName()),
                "message should name second contributor but was: " + message);
    }

    @Test
    @DisplayName("a contributor yielding a null definition fails registry construction")
    void startup_blankSegment_throws() {
        assertThrows(RuntimeException.class, () -> new DefaultActionRegistry(Set.of(new NullDefinitionContributor())));
    }

    @Test
    @DisplayName("a contributor with a definition violating the grammar fails registry construction")
    void startup_invalidGrammar_throws() {
        assertThrows(RuntimeException.class, () -> new DefaultActionRegistry(Set.of(new InvalidGrammarContributor())));
    }

    // --- helpers ---

    private static DefaultActionRegistry registryWith(ActionRef ref) {
        return new DefaultActionRegistry(Set.of(new FixedContributor(new ActionDefinition(ref))));
    }

    /** Contributor returning a fixed set of definitions. */
    private record FixedContributor(ActionDefinition definition) implements ActionContributor {
        @Override
        public Collection<ActionDefinition> actions() {
            return List.of(definition);
        }
    }

    /** Distinct named contributor providing {@code cms.content.read} — for the duplicate test. */
    private static final class FirstContributor implements ActionContributor {
        @Override
        public Collection<ActionDefinition> actions() {
            return List.of(new ActionDefinition(CMS_CONTENT_READ));
        }
    }

    /** Distinct named contributor providing the same {@code cms.content.read} — for the duplicate test. */
    private static final class SecondContributor implements ActionContributor {
        @Override
        public Collection<ActionDefinition> actions() {
            return List.of(new ActionDefinition(CMS_CONTENT_READ));
        }
    }

    /** Contributor whose {@code actions()} attempts to build a definition with a {@code null} ref. */
    private static final class NullDefinitionContributor implements ActionContributor {
        @Override
        public Collection<ActionDefinition> actions() {
            return List.of(new ActionDefinition(null));
        }
    }

    /** Contributor whose {@code actions()} attempts to build a ref that violates the grammar. */
    private static final class InvalidGrammarContributor implements ActionContributor {
        @Override
        public Collection<ActionDefinition> actions() {
            return List.of(new ActionDefinition(ActionRef.of("CMS", "content", "read")));
        }
    }
}
