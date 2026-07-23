// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.verification;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link MtlsTrustStoreVerificationSource} validation.
 *
 * <p>Verifies that {@code trustStoreId} is non-null and non-blank, and that
 * {@code trustAnchorSubjectDn} is a non-null Optional.
 */
class MtlsTrustStoreVerificationSourceTest {

    @Test
    @DisplayName("rejects null trustStoreId")
    void rejectsNullTrustStoreId() {
        assertThrows(NullPointerException.class, () -> new MtlsTrustStoreVerificationSource(null, Optional.empty()));
    }

    @Test
    @DisplayName("rejects blank trustStoreId")
    void rejectsBlankTrustStoreId() {
        assertThrows(
                IllegalArgumentException.class, () -> new MtlsTrustStoreVerificationSource("  ", Optional.empty()));
    }

    @Test
    @DisplayName("rejects null trustAnchorSubjectDn Optional")
    void rejectsNullTrustAnchorSubjectDn() {
        assertThrows(NullPointerException.class, () -> new MtlsTrustStoreVerificationSource("primary-store", null));
    }
}
