// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.authz;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PolicyStatement}.
 *
 * <p>Verifies the compact constructor rejects a {@code null} {@link Effect} or {@code null} action
 * set, and that the exposed {@code actions()} set is unmodifiable regardless of the mutability of the
 * set passed in.
 */
class PolicyStatementTest {

    private static final ActionPattern READ = new ActionPattern("cms.content.read");

    @Test
    @DisplayName("a null effect is rejected by the compact constructor")
    void nullEffect_throws() {
        assertThrows(NullPointerException.class, () -> new PolicyStatement(null, Set.of(READ)));
    }

    @Test
    @DisplayName("a null action set is rejected by the compact constructor")
    void nullActions_throws() {
        assertThrows(NullPointerException.class, () -> new PolicyStatement(Effect.ALLOW, null));
    }

    @Test
    @DisplayName("the exposed actions set is unmodifiable even when constructed from a mutable set")
    void actions_isImmutable() {
        Set<ActionPattern> mutable = new HashSet<>();
        mutable.add(READ);
        PolicyStatement statement = new PolicyStatement(Effect.ALLOW, mutable);
        Set<ActionPattern> exposed = statement.actions();
        assertThrows(UnsupportedOperationException.class, () -> exposed.add(new ActionPattern("cms.content.write")));
        // mutating the source set must not leak into the statement
        mutable.add(new ActionPattern("cms.content.delete"));
        assertTrue(statement.actions().contains(READ));
        assertTrue(statement.actions().size() == 1, "defensive copy must not observe later source mutation");
    }
}
