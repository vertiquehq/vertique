// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.hello;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

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
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.RegisterExtension;

@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class HelloResourceIT {

    @RegisterExtension
    static final VertiqueAppExtension app = VertiqueAppExtension.forFactory(new AppComponentVertiqueComponentFactory())
            .withConfig(new JsonObject()
                    .put("http", new JsonObject().put("port", 0).put("host", "127.0.0.1"))
                    .put("hello", "Hello, %s!")
                    .put("management", new JsonObject().put("enabled", false))
                    .put("jaxrs", new JsonObject().put("validationStrategy", "openapi-contract"))
                    // greetLimited() carries @RateLimited (T013); the aspect resolves its policy
                    // handle at proxy-construction time, so every app boot in this module must
                    // declare it, whether or not a test exercises the endpoint.
                    .put(
                            "rateLimit",
                            new JsonObject()
                                    .put(
                                            "policies",
                                            new JsonObject()
                                                    .put("hello-limited", RateLimitTestPolicies.helloLimited()))));

    @BeforeAll
    static void setUp() {
        String specUrl = HelloResourceIT.class.getResource("/openapi.json").toString();
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
    @DisplayName("GET /hello/{name} returns greeting")
    void getGreeting() {
        given().when()
                .get("/hello/World")
                .then()
                .statusCode(200)
                .contentType("application/json")
                .body("message", equalTo("Hello, World!"));
    }

    @Test
    @DisplayName("GET /hello returns default greeting")
    void getDefaultGreeting() {
        given().when()
                .get("/hello")
                .then()
                .statusCode(200)
                .contentType("application/json")
                .body("message", equalTo("Hello, World!"));
    }

    @Test
    @DisplayName("POST /hello with JSON body returns greeting")
    void postGreeting() {
        given().contentType("application/json")
                .body("{\"name\":\"Alice\"}")
                .when()
                .post("/hello")
                .then()
                .statusCode(200)
                .contentType("application/json")
                .body("message", equalTo("Hello, Alice!"));
    }

    @Test
    @DisplayName("DELETE /hello/{name} returns 204")
    void deleteGreeting() {
        given().when().delete("/hello/World").then().statusCode(204);
    }

    @Test
    @DisplayName("POST /hello/greetings creates greeting with 201 and Location header")
    void createGreetingResource() {
        given().contentType("application/json")
                .body("{\"name\":\"Bob\"}")
                .when()
                .post("/hello/greetings")
                .then()
                .statusCode(201)
                .header("Location", containsString("/hello/greetings/Bob"))
                .contentType("application/json")
                .body("message", equalTo("Hello, Bob!"));
    }

    @Test
    @DisplayName("GET /hello/greetings/unknown returns 404 Not Found with ProblemDetail")
    void getGreetingResourceNotFound() {
        given().when()
                .get("/hello/greetings/unknown")
                .then()
                .statusCode(404)
                .contentType("application/problem+json")
                .body("status", equalTo(404))
                .body("title", equalTo("Not Found"));
    }

    // NOTE: the /hello/limited/{name} endpoint's rate-limiting behavior (admit-then-429) is
    // retired from a hand-rolled name-length check (T013) in favor of a real @RateLimited quota,
    // proven end to end by the dedicated RateLimitedGreetingIT — including the 429/Retry-After/
    // Cache-Control contract, which no longer depends on the requested name.
}
