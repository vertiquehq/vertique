// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.verification;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ApiKeyRegistryVerificationSource} validation.
 *
 * <p>Verifies that {@code registryId} is non-null and non-blank.
 */
class ApiKeyRegistryVerificationSourceTest {

    @Test
    @DisplayName("rejects null registryId")
    void rejectsNullRegistryId() {
        assertThrows(NullPointerException.class, () -> new ApiKeyRegistryVerificationSource(null));
    }

    @Test
    @DisplayName("rejects blank registryId")
    void rejectsBlankRegistryId() {
        assertThrows(IllegalArgumentException.class, () -> new ApiKeyRegistryVerificationSource("   "));
    }
}
