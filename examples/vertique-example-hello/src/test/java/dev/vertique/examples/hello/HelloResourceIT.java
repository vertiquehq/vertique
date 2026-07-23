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
                    .put("http", new JsonObject().put("port", 0))
                    .put("hello", "Hello, %s!")
                    .put("management", new JsonObject().put("enabled", false)));

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

        RestAssured.baseURI = "http://localhost";
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

    @Test
    @DisplayName("GET /hello/limited/Ada returns greeting when name is within limit")
    void greetLimited() {
        given().when()
                .get("/hello/limited/Ada")
                .then()
                .statusCode(200)
                .contentType("application/json")
                .body("message", equalTo("Hello, Ada!"));
    }

    @Test
    @DisplayName("GET /hello/limited/Alexander returns 429 with custom Problem Detail")
    void greetLimitedExceeded() {
        given().when()
                .get("/hello/limited/Alexander")
                .then()
                .statusCode(429)
                .contentType("application/problem+json")
                .body("status", equalTo(429))
                .body("title", equalTo("Greeting Limit Exceeded"))
                .body("type", equalTo("https://example.com/problems/greeting-limit-exceeded"))
                .body("limit", equalTo(5));
    }
}
