// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.services;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import dev.vertique.application.test.VertiqueAppExtension;
import dev.vertique.rest.auth.jwt.JwtAuthFactory;
import io.restassured.RestAssured;
import io.restassured.filter.log.RequestLoggingFilter;
import io.restassured.filter.log.ResponseLoggingFilter;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.junit5.VertxExtension;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Integration proof for identity-scoped caching through the real HTTP and service pipelines.
 *
 * <p>The application is booted by {@link VertiqueAppExtension} through the generated
 * {@code AppComponentVertiqueComponentFactory}. Requests use REST Assured so authentication,
 * authorization, service dispatch, cache lookup, and response serialization are exercised
 * together.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class CacheServicesIntegrationIT {

    private static final String SYMMETRIC_KEY = "super-secret-key-for-example-app-minimum-256-bits-long!!";

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
                                                    .put(
                                                            "rate-limit-probe-shared",
                                                            RateLimitTestPolicies.probeShared())))
                    .put(
                            "cache",
                            new JsonObject()
                                    .put("enabled", true)
                                    .put("defaultMode", "LOCAL")
                                    .put("defaultTtlSeconds", 60)
                                    .put("maxTtlSeconds", 86_400)
                                    .put("jsonProfile", "vertx")
                                    .put("maxKeyBytes", 1_024)
                                    .put("maxValueBytes", 1_048_576)
                                    .put("maximumEntries", 10_000)
                                    .put("backendTimeoutMs", 100)
                                    .put(
                                            "caches",
                                            new JsonObject()
                                                    .put(
                                                            "cache-probe",
                                                            new JsonObject()
                                                                    .put("mode", "LOCAL")
                                                                    .put("ttlSeconds", 60)
                                                                    .put("jsonProfile", "vertx"))))
                    .put(
                            "services",
                            new JsonObject()
                                    .put(
                                            "contracts",
                                            new JsonObject()
                                                    .put(
                                                            "cache",
                                                            new JsonObject()
                                                                    .put(
                                                                            "probe",
                                                                            new JsonObject().put("instances", 1))))));

    private static JWTAuth jwtAuth;

    /** Configures REST Assured against the extension-managed HTTP server. */
    @BeforeAll
    static void setUp() {
        jwtAuth = JwtAuthFactory.fromSymmetricKey(app.vertx(), "HS256", SYMMETRIC_KEY);
        RestAssured.baseURI = "http://127.0.0.1";
        RestAssured.port = app.httpPort();
        RestAssured.filters(new RequestLoggingFilter(), new ResponseLoggingFilter());
    }

    /** Clears REST Assured's global configuration after the application has stopped. */
    @AfterAll
    static void tearDown() {
        RestAssured.reset();
    }

    /**
     * Verifies that the cache is reused for one actor, isolated between actors, and bypassed for
     * anonymous requests.
     */
    @Test
    @DisplayName("identity-scoped cache isolates actors and bypasses anonymous requests")
    void identityScopedCache_isolatesActors_andBypassesAnonymousRequests() {
        String aliceToken = generateToken("Alice");
        String bobToken = generateToken("Bob");

        given().header("Authorization", "Bearer " + aliceToken)
                .when()
                .get("/cache-probe/secured")
                .then()
                .statusCode(200)
                .body("actor", equalTo("Alice"))
                .body("invocation", equalTo(1));

        given().header("Authorization", "Bearer " + aliceToken)
                .when()
                .get("/cache-probe/secured")
                .then()
                .statusCode(200)
                .body("actor", equalTo("Alice"))
                .body("invocation", equalTo(1));

        given().header("Authorization", "Bearer " + bobToken)
                .when()
                .get("/cache-probe/secured")
                .then()
                .statusCode(200)
                .body("actor", equalTo("Bob"))
                .body("invocation", equalTo(2));

        given().when()
                .get("/cache-probe/public")
                .then()
                .statusCode(200)
                .body("actor", equalTo("anonymous"))
                .body("invocation", equalTo(3));

        given().when()
                .get("/cache-probe/public")
                .then()
                .statusCode(200)
                .body("actor", equalTo("anonymous"))
                .body("invocation", equalTo(4));
    }

    /**
     * Creates a token accepted by the example application's JWT provider.
     *
     * @param subject the actor identifier carried in the token subject claim
     * @return a signed JWT with the role required by the secured probe
     */
    private static String generateToken(String subject) {
        JsonObject claims = new JsonObject().put("sub", subject).put("roles", new JsonArray(List.of("user")));
        return jwtAuth.generateToken(claims);
    }
}
