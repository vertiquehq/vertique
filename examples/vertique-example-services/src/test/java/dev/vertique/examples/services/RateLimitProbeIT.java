// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.services;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import dev.vertique.application.test.VertiqueAppExtension;
import io.restassured.RestAssured;
import io.restassured.filter.log.RequestLoggingFilter;
import io.restassured.filter.log.ResponseLoggingFilter;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Proves the programmatic {@link dev.vertique.ratelimit.RateLimiters} handle and the {@code
 * @RateLimited}-annotated {@code RateLimitProbeService} resolve through the same runtime (T013,
 * TP-002, transport-neutrality proof): either path permits while the shared policy's capacity
 * remains, and both observe the same exhausted-quota outcome once it is gone — the programmatic
 * path via its own reported {@link dev.vertique.ratelimit.RateLimitDecision} outcome, the annotated
 * path via {@code RateLimitExceededException}'s T010-mapped {@code 429}.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class RateLimitProbeIT {

    /** Small, deterministic capacity shared by both probe paths' policy. */
    private static final int CAPACITY = 2;

    /** Must equal {@code RateLimitProbeService.POLICY_NAME} — the policy both probe paths share. */
    private static final String POLICY_NAME = "rate-limit-probe-shared";

    @RegisterExtension
    static final VertiqueAppExtension app = VertiqueAppExtension.forFactory(new AppComponentVertiqueComponentFactory())
            .withConfig(new JsonObject()
                    .put("http", new JsonObject().put("port", 0).put("host", "127.0.0.1"))
                    .put("management", new JsonObject().put("enabled", false))
                    .put("resilience", ResilienceTestPolicies.probeConfig())
                    .put(
                            "rateLimit",
                            new JsonObject()
                                    .put(
                                            "policies",
                                            new JsonObject()
                                                    .put(POLICY_NAME, sharedPolicy())
                                                    .put("composition", RateLimitTestPolicies.composition())))
                    .put(
                            "services",
                            new JsonObject()
                                    .put(
                                            "contracts",
                                            new JsonObject()
                                                    .put(
                                                            "rate-limit",
                                                            new JsonObject()
                                                                    .put(
                                                                            "probe",
                                                                            new JsonObject().put("instances", 1))))));

    @BeforeAll
    static void setUp() {
        RestAssured.baseURI = "http://127.0.0.1";
        RestAssured.port = app.httpPort();
        RestAssured.filters(new RequestLoggingFilter(), new ResponseLoggingFilter());
    }

    @AfterAll
    static void tearDown() {
        RestAssured.reset();
    }

    @Test
    @DisplayName("programmatic and annotated probes share the same runtime handle and exhaustion")
    void shouldAdmitProgrammaticAndAnnotatedProbesThroughTheSameRuntime() {
        given().when()
                .get("/rate-limit-probe/programmatic")
                .then()
                .statusCode(200)
                .body("outcome", equalTo("PERMITTED"));

        given().when().get("/rate-limit-probe/annotated").then().statusCode(200).body("outcome", equalTo("ADMITTED"));

        given().when()
                .get("/rate-limit-probe/programmatic")
                .then()
                .statusCode(200)
                .body("outcome", equalTo("QUOTA_EXCEEDED"));

        given().when().get("/rate-limit-probe/annotated").then().statusCode(429);
    }

    private static JsonObject sharedPolicy() {
        return new JsonObject()
                .put("enabled", true)
                .put("mode", "LOCAL")
                .put("failureMode", "OPEN")
                .put("revision", "v1")
                .put("defaultCost", 1)
                .put(
                        "algorithm",
                        new JsonObject()
                                .put("type", "TOKEN_BUCKET")
                                .put("capacity", CAPACITY)
                                .put(
                                        "refill",
                                        new JsonObject()
                                                .put("type", "GREEDY")
                                                .put("tokens", CAPACITY)
                                                .put("periodMs", 3_600_000)));
    }
}
