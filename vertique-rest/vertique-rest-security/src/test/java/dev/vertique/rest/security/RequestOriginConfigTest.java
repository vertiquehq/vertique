// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.security;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RequestOriginConfig}.
 *
 * <p>Verifies the default configuration values, defensive copy of the CIDR set, and validation
 * of the {@code forwardedForCap} parameter.
 */
class RequestOriginConfigTest {

    @Nested
    @DisplayName("defaults()")
    class Defaults {

        private final RequestOriginConfig config = RequestOriginConfig.defaults();

        @Test
        @DisplayName("trustedProxyCidrs is empty")
        void defaultTrustedProxyCidrsIsEmpty() {
            assertTrue(config.trustedProxyCidrs().isEmpty());
        }

        @Test
        @DisplayName("forwardedForCap is 16")
        void defaultForwardedForCapIs16() {
            assertEquals(RequestOriginConfig.DEFAULT_FORWARDED_FOR_CAP, config.forwardedForCap());
        }

        @Test
        @DisplayName("trustForwardedScheme is false")
        void defaultTrustForwardedSchemeIsFalse() {
            assertFalse(config.trustForwardedScheme());
        }

        @Test
        @DisplayName("trustForwardedHost is false")
        void defaultTrustForwardedHostIsFalse() {
            assertFalse(config.trustForwardedHost());
        }
    }

    @Nested
    @DisplayName("constructor validation")
    class Validation {

        @Test
        @DisplayName("negative forwardedForCap throws IllegalArgumentException")
        void negativeForwardedForCapThrows() {
            assertThrows(IllegalArgumentException.class, () -> new RequestOriginConfig(Set.of(), -1, false, false));
        }

        @Test
        @DisplayName("zero forwardedForCap is allowed (disables chain)")
        void zeroForwardedForCapIsAllowed() {
            assertDoesNotThrow(() -> new RequestOriginConfig(Set.of(), 0, false, false));
        }

        @Test
        @DisplayName("null trustedProxyCidrs throws NullPointerException")
        void nullTrustedProxyCidrsThrows() {
            assertThrows(NullPointerException.class, () -> new RequestOriginConfig(null, 16, false, false));
        }
    }

    @Nested
    @DisplayName("defensive copy of CIDR set")
    class DefensiveCopy {

        @Test
        @DisplayName("mutating the original set does not affect the config")
        void mutatingOriginalSetDoesNotAffectConfig() {
            Set<String> original = new HashSet<>();
            original.add("10.0.0.0/8");
            RequestOriginConfig config = new RequestOriginConfig(original, 16, false, false);

            original.add("192.168.0.0/16");

            assertEquals(1, config.trustedProxyCidrs().size());
            assertTrue(config.trustedProxyCidrs().contains("10.0.0.0/8"));
        }

        @Test
        @DisplayName("trustedProxyCidrs accessor returns an unmodifiable set")
        void accessorReturnsUnmodifiableSet() {
            RequestOriginConfig config = new RequestOriginConfig(Set.of("10.0.0.0/8"), 16, false, false);
            assertThrows(UnsupportedOperationException.class, () -> config.trustedProxyCidrs()
                    .add("172.16.0.0/12"));
        }
    }
}
