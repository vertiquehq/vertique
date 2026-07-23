// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DelegationGrantDecision} — the outcome record produced by
 * {@link DelegationGrantValidator} (PRD identity-002 §14.3 Contract Appendix).
 */
class DelegationGrantDecisionTest {

    @Test
    @DisplayName("a permit decision carries the grant id and the expiry that was checked")
    void permitDecisionCarriesGrantIdAndExpiry() {
        Instant expiresAt = Instant.parse("2026-08-01T00:00:00Z");
        DelegationGrantDecision decision =
                new DelegationGrantDecision(true, DelegationReasonCodes.GRANT_VALID, "grant-1", Optional.of(expiresAt));

        assertTrue(decision.permitted());
        assertEquals(DelegationReasonCodes.GRANT_VALID, decision.reasonCode());
        assertEquals("grant-1", decision.grantId());
        assertEquals(Optional.of(expiresAt), decision.expiryUsed());
    }

    @Test
    @DisplayName("compact constructor rejects a null reasonCode")
    void rejectsNullReasonCode() {
        assertThrows(
                NullPointerException.class,
                () -> new DelegationGrantDecision(false, null, "grant-1", Optional.empty()));
    }

    @Test
    @DisplayName("compact constructor rejects a blank reasonCode")
    void rejectsBlankReasonCode() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new DelegationGrantDecision(false, " ", "grant-1", Optional.empty()));
    }

    @Test
    @DisplayName("compact constructor rejects a null grantId")
    void rejectsNullGrantId() {
        assertThrows(
                NullPointerException.class,
                () -> new DelegationGrantDecision(
                        false, DelegationReasonCodes.GRANT_NOT_FOUND, null, Optional.empty()));
    }

    @Test
    @DisplayName("compact constructor rejects a null expiryUsed Optional")
    void rejectsNullExpiryUsed() {
        assertThrows(
                NullPointerException.class,
                () -> new DelegationGrantDecision(false, DelegationReasonCodes.GRANT_NOT_FOUND, "grant-1", null));
    }
}
