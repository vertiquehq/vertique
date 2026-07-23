// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SecurityRootsTest {

    @Test
    void securityRootExtendsVertiqueException() {
        assertTrue(VertiqueException.class.isAssignableFrom(VertiqueSecurityException.class));
    }

    @Test
    void unauthorizedExtendsSecurityRoot() {
        assertTrue(VertiqueSecurityException.class.isAssignableFrom(UnauthorizedException.class));
    }

    @Test
    void forbiddenExtendsSecurityRoot() {
        assertTrue(VertiqueSecurityException.class.isAssignableFrom(ForbiddenException.class));
    }

    @Test
    void preserveMessageAndCause() {
        Throwable c = new IllegalStateException("x");
        assertEquals("a", new VertiqueSecurityException("a").getMessage());
        assertSame(c, new UnauthorizedException("m", c).getCause());
        assertSame(c, new ForbiddenException("m", c).getCause());
    }
}
