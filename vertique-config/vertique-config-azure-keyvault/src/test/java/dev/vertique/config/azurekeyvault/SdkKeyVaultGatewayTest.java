// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.config.azurekeyvault;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.azure.core.exception.HttpResponseException;
import com.azure.core.http.HttpHeaders;
import com.azure.core.http.HttpRequest;
import com.azure.core.http.HttpResponse;
import dev.vertique.config.source.ConfigPropertySourceException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Unit tests for {@link SdkKeyVaultGateway#mapHttpError(String, String, String, HttpResponseException)}.
 *
 * <p>Exercises the null-response guard (NFR-CONF-002): Azure SDK credential exceptions extend
 * {@link HttpResponseException} but are constructed with a {@code null}
 * {@link com.azure.core.http.HttpResponse}. Without the null guard, calling
 * {@code e.getResponse().getStatusCode()} would itself throw a {@link NullPointerException}.
 *
 * <p>Tests are split into:
 * <ul>
 *   <li>Null-response path — simulates a {@code CredentialUnavailableException}-style exception
 *       constructed with {@code null} response. The exception message must contain the class
 *       simple name of the thrown exception type.</li>
 *   <li>Non-null response path — simulates a plain HTTP error (e.g. 403 Forbidden). The
 *       exception message must contain {@code "HTTP 403"}.</li>
 * </ul>
 */
class SdkKeyVaultGatewayTest {

    // --- Stub HttpResponse ---

    /**
     * Minimal concrete {@link HttpResponse} subclass for testing the non-null-response path.
     *
     * <p>Only {@link #getStatusCode()} is meaningful; all other abstract methods return safe
     * no-op stubs. The constructor passes {@code null} for {@link HttpRequest} — the
     * {@link SdkKeyVaultGateway#mapHttpError} method only calls {@code getStatusCode()}.
     */
    private static final class StubHttpResponse extends HttpResponse {

        private final int statusCode;

        /**
         * Constructs a stub response with the given HTTP status code.
         *
         * @param statusCode the HTTP status code to return from {@link #getStatusCode()}
         */
        StubHttpResponse(int statusCode) {
            super(null);
            this.statusCode = statusCode;
        }

        @Override
        public int getStatusCode() {
            return statusCode;
        }

        @Override
        public String getHeaderValue(String name) {
            return null;
        }

        @Override
        public HttpHeaders getHeaders() {
            return new HttpHeaders();
        }

        @Override
        public Flux<ByteBuffer> getBody() {
            return Flux.empty();
        }

        @Override
        public Mono<byte[]> getBodyAsByteArray() {
            return Mono.just(new byte[0]);
        }

        @Override
        public Mono<String> getBodyAsString() {
            return Mono.just("");
        }

        @Override
        public Mono<String> getBodyAsString(Charset charset) {
            return Mono.just("");
        }
    }

    /**
     * Simulated credential-unavailable exception — extends {@link HttpResponseException} but is
     * constructed with a {@code null} response, as the Azure SDK credential exceptions do.
     */
    private static final class StubCredentialException extends HttpResponseException {

        /**
         * Constructs a stub credential exception with a null response.
         *
         * @param message the exception message
         */
        StubCredentialException(String message) {
            super(message, null);
        }
    }

    // --- Tests ---

    @Nested
    @DisplayName("mapHttpError — null response (credential exception path)")
    class NullResponsePath {

        @Test
        @DisplayName("null response → message contains class simple name of the exception")
        void nullResponseUsesClassSimpleName() {
            StubCredentialException ex = new StubCredentialException("credential unavailable");

            ConfigPropertySourceException mapped =
                    SdkKeyVaultGateway.mapHttpError("my-vault", "db.password", "password", ex);

            assertTrue(
                    mapped.getMessage().contains("StubCredentialException"),
                    "message must contain the class simple name when response is null; was: " + mapped.getMessage());
            assertEquals("my-vault", mapped.sourceName());
            assertEquals("db.password", mapped.key());
        }

        @Test
        @DisplayName("null response → message contains the secret name and original key")
        void nullResponseMessageContainsKeyAndName() {
            StubCredentialException ex = new StubCredentialException("auth failed");

            ConfigPropertySourceException mapped =
                    SdkKeyVaultGateway.mapHttpError("vault-src", "db.api-key", "api-key", ex);

            assertTrue(
                    mapped.getMessage().contains("api-key"),
                    "message must contain the normalized secret name; was: " + mapped.getMessage());
        }
    }

    @Nested
    @DisplayName("mapHttpError — non-null response (HTTP error path)")
    class NonNullResponsePath {

        @Test
        @DisplayName("403 response → message contains 'HTTP 403'")
        void forbiddenResponseContainsHttpStatus() {
            HttpResponseException ex = new HttpResponseException("forbidden", new StubHttpResponse(403));

            ConfigPropertySourceException mapped =
                    SdkKeyVaultGateway.mapHttpError("my-vault", "db.password", "password", ex);

            assertTrue(
                    mapped.getMessage().contains("HTTP 403"),
                    "message must contain 'HTTP 403' for a 403 response; was: " + mapped.getMessage());
            assertEquals("my-vault", mapped.sourceName());
            assertEquals("db.password", mapped.key());
        }

        @Test
        @DisplayName("401 response → message contains 'HTTP 401'")
        void unauthorizedResponseContainsHttpStatus() {
            HttpResponseException ex = new HttpResponseException("unauthorized", new StubHttpResponse(401));

            ConfigPropertySourceException mapped =
                    SdkKeyVaultGateway.mapHttpError("my-vault", "db.password", "password", ex);

            assertTrue(
                    mapped.getMessage().contains("HTTP 401"),
                    "message must contain 'HTTP 401' for a 401 response; was: " + mapped.getMessage());
        }
    }
}
