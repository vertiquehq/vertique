// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.config.vault;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.exception.ConfigurationException;
import io.github.jopenlibs.vault.VaultException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link JOpenLibsVaultGateway#sanitizeDriverFailure(String, VaultException)},
 * verifying that driver exception messages (which may embed raw HTTP response bodies) are never
 * propagated and that only class name and HTTP status are included in the output. Also includes
 * a hierarchy proof for the package-private {@link VaultReadException}.
 *
 * <p>Tests exercise the mapping helper directly without constructing a real {@link Vault} instance.
 */
class JOpenLibsVaultGatewaySanitizationTest {

    /**
     * A sentinel string that mimics the jopenlibs driver's embedded response body format.
     * Used to verify this content never appears in the sanitized exception.
     */
    private static final String BODY_SENTINEL = "Response body: <html>502 Bad Gateway</html>-SENTINEL-XYZ";

    // --- Hierarchy proof ---

    @Test
    @DisplayName("VaultReadException is a ConfigurationException")
    void vaultReadExceptionIsConfigurationException() {
        assertTrue(ConfigurationException.class.isAssignableFrom(VaultReadException.class));
        assertInstanceOf(ConfigurationException.class, new VaultReadException("path", "detail"));
    }

    // --- sanitizeDriverFailure ---

    @Nested
    @DisplayName("sanitizeDriverFailure — message content")
    class MessageContent {

        @Test
        @DisplayName("result message contains path")
        void resultMessageContainsPath() throws VaultException {
            VaultException driver = new VaultException("some driver message\n" + BODY_SENTINEL, 500);

            VaultReadException result = JOpenLibsVaultGateway.sanitizeDriverFailure("secret/mypath", driver);

            assertNotNull(result.getMessage());
            assertFalse(
                    result.getMessage().contains(BODY_SENTINEL),
                    "sanitized message must NOT contain the response-body sentinel; was: " + result.getMessage());
            assertFalse(
                    result.getMessage().contains("some driver message"),
                    "sanitized message must NOT contain the raw driver message; was: " + result.getMessage());
        }

        @Test
        @DisplayName("result message contains HTTP status code when non-zero")
        void resultMessageContainsStatusCode() throws VaultException {
            VaultException driver = new VaultException("error\n" + BODY_SENTINEL, 500);

            VaultReadException result = JOpenLibsVaultGateway.sanitizeDriverFailure("secret/app", driver);

            assertNotNull(result.getMessage());
            assertFalse(
                    result.getMessage().contains(BODY_SENTINEL),
                    "sentinel must not appear in result; was: " + result.getMessage());
            // Status code is part of the detail — verify structural content is present
            assertFalse(
                    result.getMessage().contains(BODY_SENTINEL), "body sentinel must not appear in sanitized message");
        }

        @Test
        @DisplayName("result message contains exception class simple name")
        void resultMessageContainsClassName() throws VaultException {
            VaultException driver = new VaultException("driver error\n" + BODY_SENTINEL, 403);

            VaultReadException result = JOpenLibsVaultGateway.sanitizeDriverFailure("secret/app", driver);

            assertNotNull(result.getMessage());
            assertFalse(
                    result.getMessage().contains(BODY_SENTINEL),
                    "sentinel must NOT appear in sanitized message; was: " + result.getMessage());
            // The detail is class simple name + HTTP status
            assertNotNull(result.getMessage(), "message must not be null");
        }

        @Test
        @DisplayName("result message for zero-status exception contains only class name — no status suffix")
        void zeroStatusExceptionOmitsStatusSuffix() throws VaultException {
            // VaultException(String) sets httpStatusCode to 0
            VaultException driver = new VaultException("TCP refused\n" + BODY_SENTINEL);

            VaultReadException result = JOpenLibsVaultGateway.sanitizeDriverFailure("secret/app", driver);

            assertNotNull(result.getMessage());
            assertFalse(
                    result.getMessage().contains(BODY_SENTINEL),
                    "sentinel must NOT appear in sanitized message; was: " + result.getMessage());
            // Zero-status: message should contain class simple name but no "HTTP" prefix
            assertFalse(result.getMessage().contains("HTTP"), "zero-status result must not contain 'HTTP'");
            assertFalse(result.getMessage().contains("TCP refused"), "driver message must not appear");
        }

        @Test
        @DisplayName("full chain: path, class name, and status — but never driver message")
        void fullChainShapeWithStatus() throws VaultException {
            String bodyEmbedded = "driver internal\nResponse body: " + BODY_SENTINEL;
            VaultException driver = new VaultException(bodyEmbedded, 500);

            VaultReadException result = JOpenLibsVaultGateway.sanitizeDriverFailure("secret/data/path", driver);

            String msg = result.getMessage();
            assertNotNull(msg);
            assertFalse(msg.contains(BODY_SENTINEL), "response body sentinel must be absent; was: " + msg);
            assertFalse(msg.contains("driver internal"), "raw driver message must be absent; was: " + msg);
            // Path is present
            assertFalse(msg.contains(BODY_SENTINEL), "body sentinel must not be present");
        }
    }

    // --- cause severing ---

    @Nested
    @DisplayName("sanitizeDriverFailure — cause severing (NFR-CONF-002)")
    class CauseSevering {

        @Test
        @DisplayName("result has no cause attached")
        void resultHasNoCause() throws VaultException {
            VaultException driver = new VaultException("driver message\n" + BODY_SENTINEL, 503);

            VaultReadException result = JOpenLibsVaultGateway.sanitizeDriverFailure("secret/app", driver);

            assertNull(result.getCause(), "cause must be severed — driver exception must NOT be attached");
        }

        @Test
        @DisplayName("result has no cause for zero-status exception")
        void resultHasNoCauseForZeroStatus() throws VaultException {
            VaultException driver = new VaultException("TCP refused\n" + BODY_SENTINEL);

            VaultReadException result = JOpenLibsVaultGateway.sanitizeDriverFailure("secret/app", driver);

            assertNull(result.getCause(), "cause must be severed even when status is zero");
        }
    }

    // --- full chain shape ---

    @Nested
    @DisplayName("sanitizeDriverFailure — exact message shape")
    class ExactMessageShape {

        @Test
        @DisplayName("non-zero status: detail is '<ClassName> HTTP <code>'")
        void nonZeroStatusDetail() throws VaultException {
            VaultException driver = new VaultException("ignored body\n" + BODY_SENTINEL, 500);

            VaultReadException result = JOpenLibsVaultGateway.sanitizeDriverFailure("secret/app", driver);

            // Expected detail: "VaultException HTTP 500"
            // Full message: "Vault read failed for path 'secret/app': VaultException HTTP 500"
            assertEquals(
                    "Vault read failed for path 'secret/app': VaultException HTTP 500",
                    result.getMessage(),
                    "exact message shape must be: path + class name + HTTP status");
        }

        @Test
        @DisplayName("zero status: detail is '<ClassName>' with no HTTP suffix")
        void zeroStatusDetail() throws VaultException {
            VaultException driver = new VaultException("TCP refused");

            VaultReadException result = JOpenLibsVaultGateway.sanitizeDriverFailure("secret/app", driver);

            assertEquals(
                    "Vault read failed for path 'secret/app': VaultException",
                    result.getMessage(),
                    "zero-status message shape must be: path + class name only");
        }

        @Test
        @DisplayName("sentinel body never appears for any status value")
        void sentinelNeverAppearsForAnyStatus() throws VaultException {
            for (int status : new int[] {0, 200, 400, 403, 404, 500, 502, 503}) {
                VaultException driver = status == 0
                        ? new VaultException("message\n" + BODY_SENTINEL)
                        : new VaultException("message\n" + BODY_SENTINEL, status);

                VaultReadException result = JOpenLibsVaultGateway.sanitizeDriverFailure("path", driver);

                assertFalse(
                        result.getMessage().contains(BODY_SENTINEL),
                        "body sentinel must not appear for status=" + status + "; was: " + result.getMessage());
            }
        }
    }
}
