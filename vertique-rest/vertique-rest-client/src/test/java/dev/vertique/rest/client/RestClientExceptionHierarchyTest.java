// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.core.exception.TechnicalException;
import dev.vertique.core.exception.UnavailableException;
import dev.vertique.rest.client.exception.RestClientConfigurationException;
import dev.vertique.rest.client.exception.RestClientConnectionException;
import dev.vertique.rest.client.exception.RestClientException;
import dev.vertique.rest.client.exception.RestClientResponseException;
import dev.vertique.rest.client.exception.RestClientTimeoutException;
import dev.vertique.rest.client.exception.RestClientUnavailableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the REST client exception hierarchy after the semantic-root reparenting:
 *
 * <pre>
 * TechnicalException -&gt; RestClientException -&gt; RestClientResponseException
 * ConfigurationException -&gt; RestClientConfigurationException
 * UnavailableException -&gt; RestClientUnavailableException -&gt; {RestClientConnectionException,
 *                                                              RestClientTimeoutException}
 * </pre>
 *
 * <p>Each test pin the IS-A / IS-NOT-A relationship that the reparenting established, so any
 * future accidental change to an {@code extends} clause produces a clear red test here rather
 * than a silent contract regression.
 */
@DisplayName("RestClient exception hierarchy")
class RestClientExceptionHierarchyTest {

    // --- RestClientException ---

    @Test
    @DisplayName("RestClientException is a TechnicalException")
    void restClientExceptionIsTechnicalException() {
        assertTrue(TechnicalException.class.isAssignableFrom(RestClientException.class));
    }

    @Test
    @DisplayName("RestClientException is a RuntimeException (via TechnicalException → VertiqueException)")
    void restClientExceptionIsRuntimeException() {
        assertTrue(RuntimeException.class.isAssignableFrom(RestClientException.class));
    }

    @Test
    @DisplayName("RestClientException preserves message and cause")
    void restClientExceptionPreservesMessageAndCause() {
        Throwable cause = new IllegalStateException("boom");
        RestClientException ex = new RestClientException("msg", cause);
        assertEquals("msg", ex.getMessage());
        assertSame(cause, ex.getCause());
    }

    // --- RestClientResponseException ---

    @Test
    @DisplayName("RestClientResponseException is a RestClientException")
    void restClientResponseExceptionIsRestClientException() {
        assertTrue(RestClientException.class.isAssignableFrom(RestClientResponseException.class));
    }

    @Test
    @DisplayName("RestClientResponseException is a TechnicalException (via RestClientException)")
    void restClientResponseExceptionIsTechnicalException() {
        assertTrue(TechnicalException.class.isAssignableFrom(RestClientResponseException.class));
    }

    // --- RestClientConfigurationException ---

    @Test
    @DisplayName("RestClientConfigurationException is a ConfigurationException")
    void restClientConfigurationExceptionIsConfigurationException() {
        assertTrue(ConfigurationException.class.isAssignableFrom(RestClientConfigurationException.class));
    }

    @Test
    @DisplayName("RestClientConfigurationException is NOT a RestClientException")
    void restClientConfigurationExceptionIsNotRestClientException() {
        assertFalse(RestClientException.class.isAssignableFrom(RestClientConfigurationException.class));
    }

    @Test
    @DisplayName("RestClientConfigurationException preserves message and cause")
    void restClientConfigurationExceptionPreservesMessageAndCause() {
        Throwable cause = new IllegalStateException("wiring");
        RestClientConfigurationException ex = new RestClientConfigurationException("config-fail", cause);
        assertEquals("config-fail", ex.getMessage());
        assertSame(cause, ex.getCause());
    }

    // --- RestClientUnavailableException ---

    @Test
    @DisplayName("RestClientUnavailableException is an UnavailableException")
    void restClientUnavailableExceptionIsUnavailableException() {
        assertTrue(UnavailableException.class.isAssignableFrom(RestClientUnavailableException.class));
    }

    @Test
    @DisplayName("RestClientUnavailableException is a TechnicalException (via UnavailableException)")
    void restClientUnavailableExceptionIsTechnicalException() {
        assertTrue(TechnicalException.class.isAssignableFrom(RestClientUnavailableException.class));
    }

    @Test
    @DisplayName("RestClientUnavailableException is NOT a RestClientException")
    void restClientUnavailableExceptionIsNotRestClientException() {
        assertFalse(RestClientException.class.isAssignableFrom(RestClientUnavailableException.class));
    }

    @Test
    @DisplayName("RestClientUnavailableException preserves message and cause")
    void restClientUnavailableExceptionPreservesMessageAndCause() {
        Throwable cause = new IllegalStateException("net");
        RestClientUnavailableException ex = new RestClientUnavailableException("unavail", cause);
        assertEquals("unavail", ex.getMessage());
        assertSame(cause, ex.getCause());
    }

    // --- RestClientConnectionException ---

    @Test
    @DisplayName("RestClientConnectionException is a RestClientUnavailableException")
    void restClientConnectionExceptionIsRestClientUnavailableException() {
        assertTrue(RestClientUnavailableException.class.isAssignableFrom(RestClientConnectionException.class));
    }

    @Test
    @DisplayName("RestClientConnectionException is an UnavailableException")
    void restClientConnectionExceptionIsUnavailableException() {
        assertTrue(UnavailableException.class.isAssignableFrom(RestClientConnectionException.class));
    }

    @Test
    @DisplayName("RestClientConnectionException is NOT a RestClientException")
    void restClientConnectionExceptionIsNotRestClientException() {
        assertFalse(RestClientException.class.isAssignableFrom(RestClientConnectionException.class));
    }

    @Test
    @DisplayName("RestClientConnectionException preserves message and cause")
    void restClientConnectionExceptionPreservesMessageAndCause() {
        Throwable cause = new java.net.ConnectException("refused");
        RestClientConnectionException ex = new RestClientConnectionException("conn-fail", cause);
        assertEquals("conn-fail", ex.getMessage());
        assertSame(cause, ex.getCause());
    }

    // --- RestClientTimeoutException ---

    @Test
    @DisplayName("RestClientTimeoutException is a RestClientUnavailableException")
    void restClientTimeoutExceptionIsRestClientUnavailableException() {
        assertTrue(RestClientUnavailableException.class.isAssignableFrom(RestClientTimeoutException.class));
    }

    @Test
    @DisplayName("RestClientTimeoutException is an UnavailableException")
    void restClientTimeoutExceptionIsUnavailableException() {
        assertTrue(UnavailableException.class.isAssignableFrom(RestClientTimeoutException.class));
    }

    @Test
    @DisplayName("RestClientTimeoutException is NOT a RestClientException")
    void restClientTimeoutExceptionIsNotRestClientException() {
        assertFalse(RestClientException.class.isAssignableFrom(RestClientTimeoutException.class));
    }

    @Test
    @DisplayName("RestClientTimeoutException preserves message and cause")
    void restClientTimeoutExceptionPreservesMessageAndCause() {
        Throwable cause = new java.util.concurrent.TimeoutException("too slow");
        RestClientTimeoutException ex = new RestClientTimeoutException("timed-out", cause);
        assertEquals("timed-out", ex.getMessage());
        assertSame(cause, ex.getCause());
    }
}
