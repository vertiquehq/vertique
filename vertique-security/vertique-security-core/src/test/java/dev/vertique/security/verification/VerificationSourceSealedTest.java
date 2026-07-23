// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests verifying the sealed nature of {@link VerificationSource}.
 *
 * <p>Asserts that the interface is sealed and that exactly the 7 expected permits are declared.
 */
class VerificationSourceSealedTest {

    private static final Set<Class<?>> EXPECTED_PERMITS = Set.of(
            JwksVerificationSource.class,
            IntrospectionVerificationSource.class,
            ApiKeyRegistryVerificationSource.class,
            MtlsTrustStoreVerificationSource.class,
            HmacSecretResolverVerificationSource.class,
            BasicCredentialVerifierVerificationSource.class,
            CustomVerificationSource.class);

    @Test
    @DisplayName("VerificationSource is a sealed interface")
    void isSealed() {
        assertTrue(VerificationSource.class.isSealed(), "VerificationSource must be sealed");
    }

    @Test
    @DisplayName("VerificationSource has exactly 7 permitted subclasses")
    void hasExactlySevenPermits() {
        Class<?>[] permitted = VerificationSource.class.getPermittedSubclasses();
        assertEquals(7, permitted.length, "Expected exactly 7 permitted subclasses");
    }

    @Test
    @DisplayName("permitted subclasses match the expected set")
    void permittedSubclassesMatchExpected() {
        Set<Class<?>> actual =
                Arrays.stream(VerificationSource.class.getPermittedSubclasses()).collect(Collectors.toSet());
        assertEquals(EXPECTED_PERMITS, actual);
    }
}
