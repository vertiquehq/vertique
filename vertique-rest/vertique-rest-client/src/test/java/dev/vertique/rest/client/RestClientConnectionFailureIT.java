// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Parameterized integration tests for transport-level failures.
 *
 * <p>Each {@link ConnectionFailure} scenario configures WireMock to simulate a specific network
 * fault and asserts that the REST client proxy throws the expected exception subclass.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 15, unit = TimeUnit.SECONDS)
class RestClientConnectionFailureIT {

    // --- Minimal client interface ---

    @RestClient(name = "failure-test")
    @Path("/test")
    @Produces("application/json")
    interface FailureClient {
        /** Simple GET method used for all failure scenario tests. */
        @GET
        Future<String> get();
    }

    private WireMockServer wireMock;

    @BeforeEach
    void startWireMock() {
        wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMock.start();
    }

    @AfterEach
    void stopWireMock() {
        if (wireMock != null && wireMock.isRunning()) {
            wireMock.stop();
        }
    }

    /**
     * Builds a {@link FailureClient} pointing at the WireMock server with a 1-second read
     * timeout (so SOCKET_TIMEOUT fires quickly in tests).
     *
     * @param vertx the Vert.x instance
     * @return a ready-to-use test client
     */
    private FailureClient buildClient(Vertx vertx) {
        return new RestClientBuilder(vertx)
                .baseUrl("http://localhost:" + wireMock.port())
                .readTimeout(1, TimeUnit.SECONDS)
                .build(FailureClient.class);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ConnectionFailure.class)
    @DisplayName("Connection failure scenario fails with expected exception type")
    void connectionFailureScenario(ConnectionFailure scenario, Vertx vertx, VertxTestContext ctx) {
        scenario.prepare(wireMock);
        FailureClient client = buildClient(vertx);

        client.get()
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    assertThat(err)
                            .as(
                                    "scenario %s: expected %s but got %s",
                                    scenario.name(),
                                    scenario.expectedExceptionType().getSimpleName(),
                                    err.getClass().getSimpleName())
                            .isInstanceOf(scenario.expectedExceptionType());
                    ctx.completeNow();
                })));
    }
}
