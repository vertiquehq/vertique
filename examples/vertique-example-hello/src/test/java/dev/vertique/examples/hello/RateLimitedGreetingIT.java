// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.hello;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.ValidationReport;
import com.atlassian.oai.validator.restassured.OpenApiValidationFilter;
import dev.vertique.application.test.VertiqueAppExtension;
import io.restassured.RestAssured;
import io.restassured.filter.log.RequestLoggingFilter;
import io.restassured.filter.log.ResponseLoggingFilter;
import io.vertx.core.json.JsonObject;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The reactor's first AOP-on-a-JAX-RS-resource-method proof over real HTTP (T013, TP-001):
 * {@code HelloResource#greetLimited} carries {@code @RateLimited}, resolved through the generated
 * {@code $AopProxy} Dagger substitution (plan.md Pre-flight finding 1 — the proxy is reached only
 * through {@code Provider.get()}), admits requests up to a small configured capacity, then denies
 * the next one with {@code 429} carrying {@code Retry-After}/{@code Cache-Control: no-store},
 * mapped by T010's {@code RestRateLimitModule}.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class RateLimitedGreetingIT {

    /** Small, deterministic capacity — greedy refill period is long enough to stay inert. */
    private static final int CAPACITY = 2;

    @RegisterExtension
    static final VertiqueAppExtension app = VertiqueAppExtension.forFactory(new AppComponentVertiqueComponentFactory())
            .withConfig(new JsonObject()
                    .put("http", new JsonObject().put("port", 0).put("host", "127.0.0.1"))
                    .put("hello", "Hello, %s!")
                    .put("management", new JsonObject().put("enabled", false))
                    .put("jaxrs", new JsonObject().put("validationStrategy", "openapi-contract"))
                    .put(
                            "rateLimit",
                            new JsonObject()
                                    .put("policies", new JsonObject().put("hello-limited", helloLimitedPolicy()))));

    @BeforeAll
    static void setUp() {
        String specUrl =
                RateLimitedGreetingIT.class.getResource("/openapi.json").toString();
        OpenApiValidationFilter openApiFilter =
                new OpenApiValidationFilter(OpenApiInteractionValidator.createForSpecificationUrl(specUrl)
                        .withLevelResolver(LevelResolver.create()
                                .withLevel("validation.response.status.unknown", ValidationReport.Level.IGNORE)
                                .withLevel("validation.response.body.unexpected", ValidationReport.Level.IGNORE)
                                .withLevel("validation.request.security.missing", ValidationReport.Level.IGNORE)
                                .build())
                        .build());

        RestAssured.baseURI = "http://127.0.0.1";
        RestAssured.port = app.httpPort();
        RestAssured.filters(new RequestLoggingFilter(), new ResponseLoggingFilter(), openApiFilter);
    }

    @AfterAll
    static void tearDown() {
        RestAssured.reset();
    }

    @Test
    @DisplayName("admits up to capacity, then returns 429 with Retry-After and Cache-Control: no-store")
    void shouldAdmitThenReturn429OnQuotaExhaustion() {
        for (int i = 0; i < CAPACITY; i++) {
            given().when()
                    .get("/hello/limited/Ada")
                    .then()
                    .statusCode(200)
                    .contentType("application/json")
                    .body("message", equalTo("Hello, Ada!"));
        }

        given().when()
                .get("/hello/limited/Ada")
                .then()
                .statusCode(429)
                .header("Retry-After", notNullValue())
                .header("Cache-Control", equalTo("no-store"));
    }

    private static JsonObject helloLimitedPolicy() {
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
