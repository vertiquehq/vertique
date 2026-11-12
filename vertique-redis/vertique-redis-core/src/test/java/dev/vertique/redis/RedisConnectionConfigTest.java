// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Verifies the shared Redis profile validation boundary. */
class RedisConnectionConfigTest {
    @Test
    @DisplayName("accepts complete credential-free Redis profile")
    void acceptsMultipleCredentialFreeEndpoints() {
        RedisConnectionConfig config = new RedisConnectionConfig(
                "primary",
                List.of("rediss://redis-a:6380", "rediss://redis-b:6380"),
                "cache",
                "secret/ref",
                true,
                500,
                8,
                100);

        assertEquals(2, config.endpoints().size());
        assertEquals("secret/ref", config.passwordSecret());
    }

    @Test
    @DisplayName("rejects credentials in endpoints and invalid timeout bounds")
    void rejectsCredentialsAndInvalidBoundsInEndpoints() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new RedisConnectionConfig(
                        "primary", List.of("redis://user:password@redis:6379"), null, null, false, 500, 8, 100));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RedisConnectionConfig(
                        "primary", List.of("redis://redis:6379"), null, null, false, 0, 8, 100));
    }

    @Test
    @DisplayName("rejects blank names and unsupported endpoint syntax")
    void rejectsBlankNamesAndUnsupportedEndpointSyntax() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new RedisConnectionConfig(" ", List.of("redis://redis:6379"), null, null, false, 500, 8, 100));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RedisConnectionConfig(
                        "primary", List.of("http://redis:6379"), null, null, false, 500, 8, 100));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RedisConnectionConfig(
                        "primary", List.of("redis://redis:6379?password=secret"), null, null, false, 500, 8, 100));
    }

    @Test
    @DisplayName("rejects invalid pool bounds")
    void rejectsInvalidPoolBounds() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new RedisConnectionConfig(
                        "primary", List.of("redis://redis:6379"), null, null, false, 500, 0, 100));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RedisConnectionConfig(
                        "primary", List.of("redis://redis:6379"), null, null, false, 500, 8, -1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RedisConnectionConfig(
                        "primary", List.of("redis://redis:6379"), null, null, false, 500, 257, 100));
    }

    @Test
    @DisplayName("does not leak credential references in validation diagnostics")
    void doesNotLeakCredentialReferencesInValidationDiagnostics() {
        String secretReference = "vault/prod/redis-password";
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> new RedisConnectionConfig(
                        "primary",
                        List.of("redis://user:actual-password@redis:6379"),
                        null,
                        secretReference,
                        false,
                        0,
                        8,
                        100));

        assertNoCredentialInChain(failure, secretReference, "actual-password", "user");
        assertNull(failure.getCause());
        assertEquals("invalid Redis endpoint: endpoint must be a credential-free redis URI", failure.getMessage());
    }

    @Test
    @DisplayName("malformed endpoint diagnostic omits the endpoint and carries no cause")
    void malformedEndpointDiagnosticOmitsEndpointAndCarriesNoCause() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> new RedisConnectionConfig(
                        "primary", List.of("redis://user:pa ss@host:6379"), null, null, false, 500, 8, 100));

        assertNoCredentialInChain(failure, "pa ss", "user");
        assertNull(failure.getCause());
        assertEquals("invalid Redis endpoint: endpoint is not a valid URI", failure.getMessage());
    }

    @Test
    @DisplayName("blank endpoint is rejected with a field-level message")
    void blankEndpointIsRejectedWithFieldLevelMessage() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> new RedisConnectionConfig("primary", List.of(" "), null, null, false, 500, 8, 100));

        assertEquals("endpoint must not be blank", failure.getMessage());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "redis://user:pa ss@host:6379",
                "redis://user:pass@host:6379",
                "redis://user:pass@[::1]:6379",
                "redis:user:pass@host:6379",
                "redis://user:pa/ss@host:6379",
                "redis://user:pa?ss@host:6379",
                "redis://user:pa#ss@host:6379",
                "redis://user:pa@ss@host:6379",
                "redis://user:pass//***@host:6379",
                "redis://user:pass",
                "redis://:pass",
                "redis://user:pass%40host:6379",
                "redis://user:123456",
                "redis://user:0",
                "redis://user@pass",
                "redis://user:pass\uff20host:6379",
                "redis://host:6379?auth=pass",
                "redis://host:6379#user:pass",
                "redis://host:6379\nuser:pass",
                "redis://host:99999999999",
                "redis://host:abc"
            })
    @DisplayName("no invalid-endpoint diagnostic carries credential text in its message or causes")
    void invalidEndpointDiagnosticsNeverCarryCredentialText(String endpoint) {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> new RedisConnectionConfig("primary", List.of(endpoint), null, null, false, 500, 8, 100));

        assertNoCredentialInChain(failure, "pa ss", "pass", "user", "auth", "\n");
        assertTrue(
                failure.getMessage().equals("invalid Redis endpoint: endpoint is not a valid URI")
                        || failure.getMessage()
                                .equals("invalid Redis endpoint: endpoint must be a credential-free redis URI"),
                failure.getMessage());
        assertNull(failure.getCause());
    }

    private static void assertNoCredentialInChain(Throwable failure, String... credentials) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            String message = String.valueOf(current.getMessage());
            for (String credential : credentials) {
                assertFalse(message.contains(credential), () -> "leaked '" + credential + "' in: " + message);
            }
        }
    }
}
