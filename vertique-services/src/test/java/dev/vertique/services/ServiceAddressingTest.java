// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies the address and stable target id construction logic in {@link ServiceAddressing}.
 */
class ServiceAddressingTest {

    // --- buildAddress ---

    @Nested
    @DisplayName("buildAddress")
    class BuildAddress {

        @Test
        @DisplayName("includes type segment when type is non-empty")
        void withType() {
            assertEquals(
                    "services/integration/user-service/get-user",
                    ServiceAddressing.buildAddress("integration", "user-service", "get-user"));
        }

        @Test
        @DisplayName("omits type segment when type is empty string")
        void withEmptyType() {
            assertEquals(
                    "services/user-service/get-user", ServiceAddressing.buildAddress("", "user-service", "get-user"));
        }

        @Test
        @DisplayName("omits type segment when type is null")
        void withNullType() {
            assertEquals(
                    "services/user-service/get-user", ServiceAddressing.buildAddress(null, "user-service", "get-user"));
        }

        @Test
        @DisplayName("omits type segment when type is whitespace-only")
        void withWhitespaceOnlyType() {
            assertEquals(
                    "services/user-service/get-user",
                    ServiceAddressing.buildAddress("   ", "user-service", "get-user"));
        }
    }

    // --- buildBaseAddress ---

    @Nested
    @DisplayName("buildBaseAddress")
    class BuildBaseAddress {

        @Test
        @DisplayName("includes type segment when type is non-empty")
        void withType() {
            assertEquals(
                    "services/integration/user-service",
                    ServiceAddressing.buildBaseAddress("integration", "user-service"));
        }

        @Test
        @DisplayName("omits type segment when type is empty string")
        void withEmptyType() {
            assertEquals("services/user-service", ServiceAddressing.buildBaseAddress("", "user-service"));
        }

        @Test
        @DisplayName("omits type segment when type is null")
        void withNullType() {
            assertEquals("services/user-service", ServiceAddressing.buildBaseAddress(null, "user-service"));
        }
    }

    // --- buildStableTargetId ---

    @Nested
    @DisplayName("buildStableTargetId")
    class BuildStableTargetId {

        @Test
        @DisplayName("includes type segment when type is non-empty")
        void withType() {
            assertEquals(
                    "integration.user-service.get-user",
                    ServiceAddressing.buildStableTargetId("integration", "user-service", "get-user"));
        }

        @Test
        @DisplayName("omits type segment when type is empty string")
        void withEmptyType() {
            assertEquals(
                    "user-service.get-user", ServiceAddressing.buildStableTargetId("", "user-service", "get-user"));
        }

        @Test
        @DisplayName("omits type segment when type is null")
        void withNullType() {
            assertEquals(
                    "user-service.get-user", ServiceAddressing.buildStableTargetId(null, "user-service", "get-user"));
        }
    }

    // --- buildStableContractId ---

    @Nested
    @DisplayName("buildStableContractId")
    class BuildStableContractId {

        @Test
        @DisplayName("includes type segment when type is non-empty")
        void withType() {
            assertEquals(
                    "integration.user-service", ServiceAddressing.buildStableContractId("integration", "user-service"));
        }

        @Test
        @DisplayName("omits type segment when type is empty string")
        void withEmptyType() {
            assertEquals("user-service", ServiceAddressing.buildStableContractId("", "user-service"));
        }

        @Test
        @DisplayName("omits type segment when type is null")
        void withNullType() {
            assertEquals("user-service", ServiceAddressing.buildStableContractId(null, "user-service"));
        }
    }
}
