// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.resolver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link IdentityResolutionError}.
 *
 * <p>Verifies: all four constants are present and non-null; {@code valueOf} round-trips produce
 * the same constant for each declared name.
 */
class IdentityResolutionErrorTest {

    @Test
    @DisplayName("AMBIGUOUS_CLIENT_ID constant is present and non-null")
    void ambiguousClientIdPresent() {
        assertNotNull(IdentityResolutionError.AMBIGUOUS_CLIENT_ID);
    }

    @Test
    @DisplayName("INVALID_DELEGATION constant is present and non-null")
    void invalidDelegationPresent() {
        assertNotNull(IdentityResolutionError.INVALID_DELEGATION);
    }

    @Test
    @DisplayName("UNSUPPORTED_PRINCIPAL_CLASSIFICATION constant is present and non-null")
    void unsupportedPrincipalClassificationPresent() {
        assertNotNull(IdentityResolutionError.UNSUPPORTED_PRINCIPAL_CLASSIFICATION);
    }

    @Test
    @DisplayName("UNDERIVABLE_PRINCIPAL_ID constant is present and non-null")
    void underivablePrincipalIdPresent() {
        assertNotNull(IdentityResolutionError.UNDERIVABLE_PRINCIPAL_ID);
    }

    @Test
    @DisplayName("valueOf round-trips AMBIGUOUS_CLIENT_ID")
    void valueOfAmbiguousClientId() {
        assertEquals(
                IdentityResolutionError.AMBIGUOUS_CLIENT_ID, IdentityResolutionError.valueOf("AMBIGUOUS_CLIENT_ID"));
    }

    @Test
    @DisplayName("valueOf round-trips INVALID_DELEGATION")
    void valueOfInvalidDelegation() {
        assertEquals(IdentityResolutionError.INVALID_DELEGATION, IdentityResolutionError.valueOf("INVALID_DELEGATION"));
    }

    @Test
    @DisplayName("valueOf round-trips UNSUPPORTED_PRINCIPAL_CLASSIFICATION")
    void valueOfUnsupportedPrincipalClassification() {
        assertEquals(
                IdentityResolutionError.UNSUPPORTED_PRINCIPAL_CLASSIFICATION,
                IdentityResolutionError.valueOf("UNSUPPORTED_PRINCIPAL_CLASSIFICATION"));
    }

    @Test
    @DisplayName("valueOf round-trips UNDERIVABLE_PRINCIPAL_ID")
    void valueOfUnderivablePrincipalId() {
        assertEquals(
                IdentityResolutionError.UNDERIVABLE_PRINCIPAL_ID,
                IdentityResolutionError.valueOf("UNDERIVABLE_PRINCIPAL_ID"));
    }
}
