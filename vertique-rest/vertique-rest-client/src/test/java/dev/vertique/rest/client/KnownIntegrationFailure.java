// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

import com.github.tomakehurst.wiremock.junit.Stubbing;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import dev.vertique.rest.client.exception.RestClientException;
import dev.vertique.rest.client.exception.RestClientResponseException;

/**
 * Parameterized HTTP-level failure scenarios for {@link RestClientResponseFailureIT}.
 *
 * <p>Each constant configures a WireMock stub for a specific problematic response condition and
 * declares the exception class the REST client proxy is expected to throw.
 */
public enum KnownIntegrationFailure implements WiremockScenarioPreparer {

    /**
     * Server returns 200 with {@code text/html} content type.
     * When an {@code @ExpectedStatus} expectation requires JSON, this produces a failure.
     * When no expectation is set, the deserialization fails.
     * Expected: {@link RestClientException} (deserialization fails for non-JSON).
     */
    HTML_RESPONSE {
        @Override
        public StubMapping prepare(Stubbing server) {
            return server.stubFor(get(urlEqualTo("/test"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "text/html")
                            .withBody("<html><body>error</body></html>")));
        }

        @Override
        public Class<? extends Throwable> expectedExceptionType() {
            return RestClientException.class;
        }
    },

    /**
     * Server returns 200 with malformed JSON body.
     * Expected: {@link RestClientException} (Jackson parse failure).
     */
    MALFORMED_JSON {
        @Override
        public StubMapping prepare(Stubbing server) {
            return server.stubFor(get(urlEqualTo("/test"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("{")));
        }

        @Override
        public Class<? extends Throwable> expectedExceptionType() {
            return RestClientException.class;
        }
    },

    /**
     * Server returns 400 Bad Request with a JSON body.
     * Expected: {@link RestClientResponseException} with status 400.
     */
    STANDARD_400 {
        @Override
        public StubMapping prepare(Stubbing server) {
            return server.stubFor(get(urlEqualTo("/test"))
                    .willReturn(aResponse()
                            .withStatus(400)
                            .withHeader("Content-Type", "application/json")
                            .withBody("{\"error\":\"bad request\"}")));
        }

        @Override
        public Class<? extends Throwable> expectedExceptionType() {
            return RestClientResponseException.class;
        }
    },

    /**
     * Server returns 401 Unauthorized.
     * Expected: {@link RestClientResponseException} with status 401.
     */
    STANDARD_401 {
        @Override
        public StubMapping prepare(Stubbing server) {
            return server.stubFor(get(urlEqualTo("/test"))
                    .willReturn(aResponse()
                            .withStatus(401)
                            .withHeader("Content-Type", "application/json")
                            .withBody("{\"error\":\"unauthorized\"}")));
        }

        @Override
        public Class<? extends Throwable> expectedExceptionType() {
            return RestClientResponseException.class;
        }
    },

    /**
     * Server returns 403 Forbidden.
     * Expected: {@link RestClientResponseException} with status 403.
     */
    STANDARD_403 {
        @Override
        public StubMapping prepare(Stubbing server) {
            return server.stubFor(get(urlEqualTo("/test"))
                    .willReturn(aResponse()
                            .withStatus(403)
                            .withHeader("Content-Type", "application/json")
                            .withBody("{\"error\":\"forbidden\"}")));
        }

        @Override
        public Class<? extends Throwable> expectedExceptionType() {
            return RestClientResponseException.class;
        }
    },

    /**
     * Server returns 404 Not Found.
     * Expected: {@link RestClientResponseException} with status 404 (non-Optional return type).
     */
    STANDARD_404 {
        @Override
        public StubMapping prepare(Stubbing server) {
            return server.stubFor(get(urlEqualTo("/test"))
                    .willReturn(aResponse()
                            .withStatus(404)
                            .withHeader("Content-Type", "application/json")
                            .withBody("{\"error\":\"not found\"}")));
        }

        @Override
        public Class<? extends Throwable> expectedExceptionType() {
            return RestClientResponseException.class;
        }
    },

    /**
     * Server returns 409 Conflict.
     * Expected: {@link RestClientResponseException} with status 409.
     */
    STANDARD_409 {
        @Override
        public StubMapping prepare(Stubbing server) {
            return server.stubFor(get(urlEqualTo("/test"))
                    .willReturn(aResponse()
                            .withStatus(409)
                            .withHeader("Content-Type", "application/json")
                            .withBody("{\"error\":\"conflict\"}")));
        }

        @Override
        public Class<? extends Throwable> expectedExceptionType() {
            return RestClientResponseException.class;
        }
    },

    /**
     * Server returns 429 Too Many Requests.
     * Expected: {@link RestClientResponseException} with status 429.
     */
    STANDARD_429 {
        @Override
        public StubMapping prepare(Stubbing server) {
            return server.stubFor(get(urlEqualTo("/test"))
                    .willReturn(aResponse()
                            .withStatus(429)
                            .withHeader("Content-Type", "application/json")
                            .withBody("{\"error\":\"rate limited\"}")));
        }

        @Override
        public Class<? extends Throwable> expectedExceptionType() {
            return RestClientResponseException.class;
        }
    },

    /**
     * Server returns 500 with a JSON body.
     * Expected: {@link RestClientResponseException} with {@code isServerError()==true}.
     */
    SERVER_ERROR_JSON {
        @Override
        public StubMapping prepare(Stubbing server) {
            return server.stubFor(get(urlEqualTo("/test"))
                    .willReturn(aResponse()
                            .withStatus(500)
                            .withHeader("Content-Type", "application/json")
                            .withBody("{\"error\":\"internal error\"}")));
        }

        @Override
        public Class<? extends Throwable> expectedExceptionType() {
            return RestClientResponseException.class;
        }
    },

    /**
     * Server returns 500 with an HTML body.
     * Expected: {@link RestClientResponseException} with {@code isServerError()==true}.
     */
    SERVER_ERROR_HTML {
        @Override
        public StubMapping prepare(Stubbing server) {
            return server.stubFor(get(urlEqualTo("/test"))
                    .willReturn(aResponse()
                            .withStatus(500)
                            .withHeader("Content-Type", "text/html")
                            .withBody("<html>Internal Server Error</html>")));
        }

        @Override
        public Class<? extends Throwable> expectedExceptionType() {
            return RestClientResponseException.class;
        }
    },

    /**
     * Server returns 502 Bad Gateway with HTML body.
     * Expected: {@link RestClientResponseException} with status 502.
     */
    BAD_GATEWAY_HTML {
        @Override
        public StubMapping prepare(Stubbing server) {
            return server.stubFor(get(urlEqualTo("/test"))
                    .willReturn(aResponse()
                            .withStatus(502)
                            .withHeader("Content-Type", "text/html")
                            .withBody("<html>Bad Gateway</html>")));
        }

        @Override
        public Class<? extends Throwable> expectedExceptionType() {
            return RestClientResponseException.class;
        }
    },

    /**
     * Server returns 503 Service Unavailable with HTML body.
     * Expected: {@link RestClientResponseException} with status 503.
     */
    SERVICE_UNAVAILABLE_HTML {
        @Override
        public StubMapping prepare(Stubbing server) {
            return server.stubFor(get(urlEqualTo("/test"))
                    .willReturn(aResponse()
                            .withStatus(503)
                            .withHeader("Content-Type", "text/html")
                            .withBody("<html>Service Unavailable</html>")));
        }

        @Override
        public Class<? extends Throwable> expectedExceptionType() {
            return RestClientResponseException.class;
        }
    },

    /**
     * Server returns 504 Gateway Timeout with a JSON body.
     * Expected: {@link RestClientResponseException} with status 504.
     */
    GATEWAY_TIMEOUT {
        @Override
        public StubMapping prepare(Stubbing server) {
            return server.stubFor(get(urlEqualTo("/test"))
                    .willReturn(aResponse()
                            .withStatus(504)
                            .withHeader("Content-Type", "application/json")
                            .withBody("{\"error\":\"gateway timeout\"}")));
        }

        @Override
        public Class<? extends Throwable> expectedExceptionType() {
            return RestClientResponseException.class;
        }
    },

    /**
     * Server returns 400 with {@code application/problem+json} content type.
     * Expected: {@link RestClientResponseException} whose {@code problemDetail()} is parseable.
     */
    PROBLEM_JSON_400 {
        @Override
        public StubMapping prepare(Stubbing server) {
            return server.stubFor(get(urlEqualTo("/test"))
                    .willReturn(aResponse()
                            .withStatus(400)
                            .withHeader("Content-Type", "application/problem+json")
                            .withBody("{\"type\":\"about:blank\",\"title\":\"Bad Request\","
                                    + "\"status\":400,\"detail\":\"validation failed\"}")));
        }

        @Override
        public Class<? extends Throwable> expectedExceptionType() {
            return RestClientResponseException.class;
        }
    },

    /**
     * Server returns 409 with {@code application/problem+json} content type.
     * Expected: {@link RestClientResponseException} whose {@code problemDetail()} is parseable.
     */
    PROBLEM_JSON_409 {
        @Override
        public StubMapping prepare(Stubbing server) {
            return server.stubFor(get(urlEqualTo("/test"))
                    .willReturn(aResponse()
                            .withStatus(409)
                            .withHeader("Content-Type", "application/problem+json")
                            .withBody("{\"type\":\"about:blank\",\"title\":\"Conflict\","
                                    + "\"status\":409,\"detail\":\"duplicate key\"}")));
        }

        @Override
        public Class<? extends Throwable> expectedExceptionType() {
            return RestClientResponseException.class;
        }
    },

    /**
     * Server returns 500 with {@code application/problem+json} content type.
     * Expected: {@link RestClientResponseException} with parseable problem detail.
     */
    PROBLEM_JSON_500 {
        @Override
        public StubMapping prepare(Stubbing server) {
            return server.stubFor(get(urlEqualTo("/test"))
                    .willReturn(aResponse()
                            .withStatus(500)
                            .withHeader("Content-Type", "application/problem+json")
                            .withBody("{\"type\":\"about:blank\",\"title\":\"Internal Server Error\","
                                    + "\"status\":500,\"detail\":\"unexpected error\"}")));
        }

        @Override
        public Class<? extends Throwable> expectedExceptionType() {
            return RestClientResponseException.class;
        }
    },

    /**
     * Server returns 503 with {@code application/problem+json} content type.
     * Expected: {@link RestClientResponseException} with parseable problem detail.
     */
    PROBLEM_JSON_503 {
        @Override
        public StubMapping prepare(Stubbing server) {
            return server.stubFor(get(urlEqualTo("/test"))
                    .willReturn(aResponse()
                            .withStatus(503)
                            .withHeader("Content-Type", "application/problem+json")
                            .withBody("{\"type\":\"about:blank\",\"title\":\"Service Unavailable\","
                                    + "\"status\":503,\"detail\":\"overloaded\"}")));
        }

        @Override
        public Class<? extends Throwable> expectedExceptionType() {
            return RestClientResponseException.class;
        }
    },
}
