// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.scope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.context.ContextValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ScopeContext} composition, value semantics, and ContextValue eligibility.
 */
class ScopeContextTest {

    @Test
    @DisplayName("composes a non-null key with value semantics")
    void composesNonNullKeyWithValueSemantics() {
        ScopeKey acme = new ScopeKey("acme");
        ScopeKey otherAcme = new ScopeKey(new String("acme"));
        ScopeKey globex = new ScopeKey("globex");

        ScopeContext left = new ScopeContext(acme);
        ScopeContext right = new ScopeContext(otherAcme);
        ScopeContext different = new ScopeContext(globex);

        assertSame(acme, left.scopeKey());
        assertEquals(acme, left.scopeKey());
        assertEquals(left, right);
        assertEquals(left.hashCode(), right.hashCode());
        assertNotEquals(left, different);

        ScopeContext defaultContext = new ScopeContext(ScopeKey.DEFAULT);
        assertEquals(ScopeKey.DEFAULT, defaultContext.scopeKey());
        assertEquals(new ScopeContext(new ScopeKey("default")), defaultContext);

        ScopeContext systemContext = new ScopeContext(new ScopeKey("system"));
        assertEquals(new ScopeKey("system"), systemContext.scopeKey());
        assertNotEquals(defaultContext, systemContext);

        assertThrows(NullPointerException.class, () -> new ScopeContext(null));

        ContextValue asContextValue = left;
        assertTrue(asContextValue instanceof ScopeContext);
        assertTrue(ContextValue.class.isAssignableFrom(ScopeContext.class));
        assertFalse(ContextValue.class.isAssignableFrom(ScopeKey.class));
    }
}
