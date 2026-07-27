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
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Server-boot integration tests for {@code JsonProfilesDemoResource}, proving the {@code vertique}
 * and {@code vertique-strict} JSON mapper profiles' wire behavior end to end (json-004, slice S6).
 *
 * <p>Mirrors {@link HelloResourceIT}'s harness — a real {@code VertiqueAppExtension}-booted server,
 * RestAssured, and the same {@link OpenApiValidationFilter} wired against the compile-time
 * generated {@code /openapi.json} on the classpath.
 *
 * <p><strong>Expected red before the green phase:</strong> {@code pom.xml}'s
 * {@code modelConverterClasses} does not yet register {@code BigDecimalModelConverter}, so the
 * generated spec still declares {@code PriceQuote.amount}/{@code discount} as JSON {@code number}
 * schemas. The {@code /price} endpoints' actual wire form under the {@code vertique-strict} profile
 * is a JSON <em>string</em> (e.g. {@code "amount":"1.50"}) — a genuine spec/wire mismatch that the
 * {@link OpenApiValidationFilter} is expected to flag as a validation failure. That failure is the
 * intended red-phase proof of the mismatch, not a defect in this test.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class JsonProfilesDemoIT {

    @RegisterExtension
    static final VertiqueAppExtension app = VertiqueAppExtension.forFactory(new AppComponentVertiqueComponentFactory())
            .withConfig(new JsonObject()
                    .put("http", new JsonObject().put("port", 0))
                    .put("hello", "Hello, %s!")
                    .put("management", new JsonObject().put("enabled", false)));

    @BeforeAll
    static void setUp() {
        String specUrl = JsonProfilesDemoIT.class.getResource("/openapi.json").toString();
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

    // --- vertique profile: GET /json-demo/optional ---

    @Test
    @DisplayName("GET /json-demo/optional returns present optional fields")
    void getOptionalGreeting_present() {
        given().when()
                .get("/json-demo/optional")
                .then()
                .statusCode(200)
                .contentType("application/json")
                .body("name", equalTo("Ada"))
                .body("nickname", equalTo("zed"))
                .body("tags", equalTo(java.util.List.of("a", "b")))
                .body("rank", equalTo(7));
    }

    @Test
    @DisplayName("GET /json-demo/optional?empty=true omits empty optional fields entirely")
    void getOptionalGreeting_empty() {
        Map<String, Object> body = given().when()
                .get("/json-demo/optional?empty=true")
                .then()
                .statusCode(200)
                .contentType("application/json")
                .extract()
                .jsonPath()
                .getMap("");

        org.junit.jupiter.api.Assertions.assertEquals("Ada", body.get("name"), "name must still be present");
        org.junit.jupiter.api.Assertions.assertFalse(
                body.containsKey("nickname"), "nickname must be omitted when empty");
        org.junit.jupiter.api.Assertions.assertFalse(body.containsKey("tags"), "tags must be omitted when empty");
        org.junit.jupiter.api.Assertions.assertFalse(body.containsKey("rank"), "rank must be omitted when empty");
    }

    // --- vertique profile: POST /json-demo/optional ---

    @Test
    @DisplayName("POST /json-demo/optional with nickname omitted binds Optional.empty()")
    void postOptionalGreeting_nicknameOmitted() {
        given().contentType("application/json")
                .body("{\"name\":\"Ada\"}")
                .when()
                .post("/json-demo/optional")
                .then()
                .statusCode(200)
                .contentType("application/json")
                .body("nicknamePresent", equalTo(false));
    }

    @Test
    @DisplayName("POST /json-demo/optional with an explicit null nickname binds Optional.empty()")
    void postOptionalGreeting_nicknameNull() {
        given().contentType("application/json")
                .body("{\"name\":\"Ada\",\"nickname\":null}")
                .when()
                .post("/json-demo/optional")
                .then()
                .statusCode(200)
                .contentType("application/json")
                .body("nicknamePresent", equalTo(false));
    }

    @Test
    @DisplayName("POST /json-demo/optional with a present nickname binds Optional.of(value)")
    void postOptionalGreeting_nicknamePresent() {
        given().contentType("application/json")
                .body("{\"name\":\"Ada\",\"nickname\":\"zed\"}")
                .when()
                .post("/json-demo/optional")
                .then()
                .statusCode(200)
                .contentType("application/json")
                .body("nicknamePresent", equalTo(true))
                .body("nickname", equalTo("zed"));
    }

    // --- vertique-strict profile: GET /json-demo/price ---

    @Test
    @DisplayName("GET /json-demo/price returns amount and discount as plain-decimal JSON strings")
    void getPriceQuote_present() {
        given().when()
                .get("/json-demo/price")
                .then()
                .statusCode(200)
                .contentType("application/json")
                .body(containsString("\"amount\":\"1.50\""))
                .body(containsString("\"discount\":\"0.25\""));
    }

    @Test
    @DisplayName("GET /json-demo/price?nodiscount=true omits the discount property entirely")
    void getPriceQuote_noDiscount() {
        Map<String, Object> body = given().when()
                .get("/json-demo/price?nodiscount=true")
                .then()
                .statusCode(200)
                .contentType("application/json")
                .extract()
                .jsonPath()
                .getMap("");

        org.junit.jupiter.api.Assertions.assertFalse(
                body.containsKey("discount"), "discount must be omitted when empty");
    }

    // --- vertique-strict profile: POST /json-demo/price ---

    @Test
    @DisplayName("POST /json-demo/price with a plain-decimal string amount binds full precision")
    void postPriceQuote_stringAmount_binds() {
        given().contentType("application/json")
                .body("{\"sku\":\"SKU-1\",\"amount\":\"1.50\"}")
                .when()
                .post("/json-demo/price")
                .then()
                .statusCode(200)
                .contentType("application/json")
                .body("scale", equalTo(2))
                .body("plain", equalTo("1.50"));
    }

    @Test
    @DisplayName("POST /json-demo/price with a numeric (non-string) amount is rejected with 400")
    void postPriceQuote_numericAmount_rejected() {
        given().contentType("application/json")
                .body("{\"sku\":\"SKU-1\",\"amount\":1.5}")
                .when()
                .post("/json-demo/price")
                .then()
                .statusCode(400);
    }
}
