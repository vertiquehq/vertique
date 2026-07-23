// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.authz;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PolicyDefinition}.
 *
 * <p>Verifies the compact constructor rejects a {@code null} name and a {@code null} statement list,
 * and that the exposed {@code statements()} list is unmodifiable regardless of the mutability of the
 * list passed in.
 */
class PolicyDefinitionTest {

    private static final PolicyStatement STATEMENT =
            new PolicyStatement(Effect.ALLOW, Set.of(new ActionPattern("cms.content.read")));

    @Test
    @DisplayName("a null name is rejected by the compact constructor")
    void nullName_throws() {
        assertThrows(NullPointerException.class, () -> new PolicyDefinition(null, List.of(STATEMENT)));
    }

    @Test
    @DisplayName("a null statement list is rejected by the compact constructor")
    void nullStatements_throws() {
        assertThrows(NullPointerException.class, () -> new PolicyDefinition("p", null));
    }

    @Test
    @DisplayName("the exposed statements list is unmodifiable even when constructed from a mutable list")
    void statements_isImmutable() {
        List<PolicyStatement> mutable = new ArrayList<>();
        mutable.add(STATEMENT);
        PolicyDefinition definition = new PolicyDefinition("p", mutable);
        List<PolicyStatement> exposed = definition.statements();
        assertThrows(UnsupportedOperationException.class, () -> exposed.add(STATEMENT));
        // mutating the source list must not leak into the definition
        mutable.add(STATEMENT);
        assertTrue(definition.statements().size() == 1, "defensive copy must not observe later source mutation");
    }
}
