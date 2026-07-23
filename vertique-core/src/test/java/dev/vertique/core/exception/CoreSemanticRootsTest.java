// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CoreSemanticRootsTest {

    @Test
    void conflictExtendsVertiqueException() {
        assertTrue(VertiqueException.class.isAssignableFrom(ConflictException.class));
    }

    @Test
    void notFoundExtendsVertiqueException() {
        assertTrue(VertiqueException.class.isAssignableFrom(NotFoundException.class));
    }

    @Test
    void businessRuleExtendsValidationException() {
        assertTrue(ValidationException.class.isAssignableFrom(BusinessRuleException.class));
        assertTrue(VertiqueException.class.isAssignableFrom(BusinessRuleException.class));
    }

    @Test
    void conflictPreservesMessageAndCause() {
        Throwable cause = new IllegalStateException("boom");
        ConflictException ex = new ConflictException("conflict", cause);
        assertEquals("conflict", ex.getMessage());
        assertSame(cause, ex.getCause());
    }

    @Test
    void notFoundPreservesMessageAndCause() {
        Throwable cause = new IllegalStateException("boom");
        NotFoundException ex = new NotFoundException("missing", cause);
        assertEquals("missing", ex.getMessage());
        assertSame(cause, ex.getCause());
    }

    @Test
    void businessRulePreservesMessageAndCause() {
        Throwable cause = new IllegalStateException("boom");
        BusinessRuleException ex = new BusinessRuleException("rule", cause);
        assertEquals("rule", ex.getMessage());
        assertSame(cause, ex.getCause());
    }

    @Test
    void rootsAreConcreteAndInstantiableViaMessageCtor() {
        assertEquals("a", new ConflictException("a").getMessage());
        assertEquals("b", new NotFoundException("b").getMessage());
        assertEquals("c", new BusinessRuleException("c").getMessage());
    }
}
