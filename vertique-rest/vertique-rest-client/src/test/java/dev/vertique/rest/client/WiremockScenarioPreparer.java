// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;

/**
 * SPI for parameterized WireMock failure scenario setup.
 *
 * <p>Each implementation configures a specific failure condition on the WireMock server and
 * declares the expected exception type that the REST client proxy should throw.
 *
 * <p>Used by {@link RestClientConnectionFailureIT} and {@link RestClientResponseFailureIT} to drive
 * {@code @ParameterizedTest} cases from enum constants.
 */
public interface WiremockScenarioPreparer {

    /**
     * Configures the WireMock server with stubs required for this scenario and returns the
     * registered stub mapping.
     *
     * @param server the WireMock server to configure
     * @return the registered stub mapping
     */
    StubMapping prepare(WireMockServer server);

    /**
     * Returns the exception type that the REST client is expected to throw for this scenario.
     *
     * @return the expected exception class; must be a subclass of {@link Throwable}
     */
    Class<? extends Throwable> expectedExceptionType();
}
