// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit.Stubbing;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import dev.vertique.rest.client.exception.RestClientConnectionException;
import dev.vertique.rest.client.exception.RestClientTimeoutException;
import dev.vertique.rest.client.exception.RestClientUnavailableException;

/**
 * Parameterized transport-level failure scenarios for {@link RestClientConnectionFailureIT}.
 *
 * <p>Each constant configures a WireMock server to simulate a specific network fault and declares
 * the exception class the REST client is expected to throw.
 */
public enum ConnectionFailure implements WiremockScenarioPreparer {

    /**
     * The server accepts the connection but does not respond within the client's read timeout.
     * Expected: {@link RestClientTimeoutException}.
     */
    SOCKET_TIMEOUT {
        @Override
        public StubMapping prepare(Stubbing server) {
            return server.stubFor(get(urlEqualTo("/test"))
                    .willReturn(aResponse().withStatus(200).withFixedDelay(60_000))); // 60 s delay > 1 s client timeout
        }

        @Override
        public Class<? extends Throwable> expectedExceptionType() {
            return RestClientTimeoutException.class;
        }
    },

    /**
     * The server sends an empty response (no HTTP data) and closes the connection.
     * Expected: {@link RestClientConnectionException}.
     */
    EMPTY_RESPONSE {
        @Override
        public StubMapping prepare(Stubbing server) {
            return server.stubFor(
                    get(urlEqualTo("/test")).willReturn(aResponse().withFault(Fault.EMPTY_RESPONSE)));
        }

        @Override
        public Class<? extends Throwable> expectedExceptionType() {
            return RestClientConnectionException.class;
        }
    },

    /**
     * The server resets the connection by peer after accepting it.
     * Expected: {@link RestClientConnectionException}.
     */
    CONNECTION_RESET {
        @Override
        public StubMapping prepare(Stubbing server) {
            return server.stubFor(
                    get(urlEqualTo("/test")).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
        }

        @Override
        public Class<? extends Throwable> expectedExceptionType() {
            return RestClientConnectionException.class;
        }
    },

    /**
     * The server sends random bytes then closes the connection.
     * Expected: {@link RestClientUnavailableException} — the random data causes a
     * connection-close fault, which maps to {@link RestClientConnectionException}
     * (a {@link RestClientUnavailableException} subclass). The connection is lost
     * before a usable HTTP response is received, so this is a transport-level
     * unavailability, not a response-level failure.
     */
    RANDOM_BYTES {
        @Override
        public StubMapping prepare(Stubbing server) {
            return server.stubFor(
                    get(urlEqualTo("/test")).willReturn(aResponse().withFault(Fault.RANDOM_DATA_THEN_CLOSE)));
        }

        @Override
        public Class<? extends Throwable> expectedExceptionType() {
            return RestClientUnavailableException.class;
        }
    },
}
