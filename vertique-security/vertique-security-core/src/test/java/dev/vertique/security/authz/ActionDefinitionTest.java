// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.authz;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ActionDefinition}.
 *
 * <p>Verifies that the record exposes its {@link ActionRef} component and that the compact
 * constructor rejects a {@code null} ref.
 */
class ActionDefinitionTest {

    @Test
    @DisplayName("ref() returns the wrapped ActionRef")
    void ref_nonNull() {
        ActionRef ref = ActionRef.of("a", "b", "c");
        assertSame(ref, new ActionDefinition(ref).ref());
    }

    @Test
    @DisplayName("constructing with a null ref throws")
    void nullRef_throws() {
        assertThrows(RuntimeException.class, () -> new ActionDefinition(null));
    }
}
