// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.resolver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.exception.VertiqueSecurityException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link IdentityResolutionException}.
 *
 * <p>Verifies: two-arg constructor stores error and message; three-arg constructor stores error,
 * message, and cause; {@code error()} returns the typed enum constant; null {@code error} and
 * null {@code message} are rejected; {@code getMessage()} returns the passed message string;
 * and that the class is assignable to {@link VertiqueSecurityException} (hierarchy proof).
 */
class IdentityResolutionExceptionTest {

    // --- hierarchy proof ---

    @Test
    @DisplayName("IdentityResolutionException is a VertiqueSecurityException")
    void isVertiqueSecurityException() {
        assertTrue(VertiqueSecurityException.class.isAssignableFrom(IdentityResolutionException.class));
        assertInstanceOf(
                VertiqueSecurityException.class,
                new IdentityResolutionException(IdentityResolutionError.AMBIGUOUS_CLIENT_ID, "msg"));
    }

    // --- two-arg constructor ---

    @Test
    @DisplayName("two-arg constructor stores error and message")
    void twoArgConstructorStoresFields() {
        IdentityResolutionException ex =
                new IdentityResolutionException(IdentityResolutionError.AMBIGUOUS_CLIENT_ID, "duplicate client id");

        assertEquals(IdentityResolutionError.AMBIGUOUS_CLIENT_ID, ex.error());
        assertEquals("duplicate client id", ex.getMessage());
    }

    // --- three-arg constructor ---

    @Test
    @DisplayName("three-arg constructor stores error, message and cause")
    void threeArgConstructorStoresFields() {
        RuntimeException cause = new RuntimeException("underlying");
        IdentityResolutionException ex =
                new IdentityResolutionException(IdentityResolutionError.INVALID_DELEGATION, "bad grant", cause);

        assertEquals(IdentityResolutionError.INVALID_DELEGATION, ex.error());
        assertEquals("bad grant", ex.getMessage());
        assertSame(cause, ex.getCause());
    }

    // --- error() accessor ---

    @Test
    @DisplayName("error() returns the typed IdentityResolutionError")
    void errorAccessorReturnsTypedError() {
        IdentityResolutionException ex = new IdentityResolutionException(
                IdentityResolutionError.UNSUPPORTED_PRINCIPAL_CLASSIFICATION, "unsupported");

        assertNotNull(ex.error());
        assertEquals(IdentityResolutionError.UNSUPPORTED_PRINCIPAL_CLASSIFICATION, ex.error());
    }

    // --- null rejections ---

    @Test
    @DisplayName("null error rejected in two-arg constructor")
    void rejectsNullErrorTwoArg() {
        assertThrows(NullPointerException.class, () -> new IdentityResolutionException(null, "msg"));
    }

    @Test
    @DisplayName("null message rejected in two-arg constructor")
    void rejectsNullMessageTwoArg() {
        assertThrows(
                NullPointerException.class,
                () -> new IdentityResolutionException(IdentityResolutionError.AMBIGUOUS_CLIENT_ID, null));
    }

    @Test
    @DisplayName("null error rejected in three-arg constructor")
    void rejectsNullErrorThreeArg() {
        assertThrows(
                NullPointerException.class, () -> new IdentityResolutionException(null, "msg", new RuntimeException()));
    }

    @Test
    @DisplayName("null message rejected in three-arg constructor")
    void rejectsNullMessageThreeArg() {
        assertThrows(
                NullPointerException.class,
                () -> new IdentityResolutionException(
                        IdentityResolutionError.INVALID_DELEGATION, null, new RuntimeException()));
    }

    // --- getMessage ---

    @Test
    @DisplayName("getMessage() returns the message passed to the constructor")
    void getMessageReturnsPassedMessage() {
        String message = "something went wrong";
        IdentityResolutionException ex =
                new IdentityResolutionException(IdentityResolutionError.AMBIGUOUS_CLIENT_ID, message);

        assertEquals(message, ex.getMessage());
    }
}
