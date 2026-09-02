// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.hello;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
 * <p><strong>Spec/wire alignment.</strong> Both this filter and — because the application selects
 * the {@code openapi-contract} validation strategy — the server itself validate against the
 * generated spec, so the spec must describe the profiles' real wire form. {@code pom.xml} therefore
 * registers {@code dev.vertique.openapi.BigDecimalModelConverter} (so {@code PriceQuote.amount}/
 * {@code discount} are decimal <em>strings</em>, matching {@code vertique-strict}) and
 * {@code dev.vertique.openapi.ScalarOptionalModelConverter} (so {@code OptionalGreeting.rank} is a
 * scalar {@code integer}, matching the {@code Jdk8Module} wire form) alongside
 * {@code FutureModelConverter}. The converters and the strategy are one composition: the default
 * {@code web-validation} strategy synthesizes schemas from the Java types and is profile-agnostic,
 * so it would reject a correct {@code vertique-strict} decimal-string body.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class JsonProfilesDemoIT {

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
                                    .put(
                                            "policies",
                                            new JsonObject()
                                                    .put("hello-limited", RateLimitTestPolicies.helloLimited()))));

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

        RestAssured.baseURI = "http://127.0.0.1";
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

        assertEquals("Ada", body.get("name"), "name must still be present");
        assertFalse(body.containsKey("nickname"), "nickname must be omitted when empty");
        assertFalse(body.containsKey("tags"), "tags must be omitted when empty");
        assertFalse(body.containsKey("rank"), "rank must be omitted when empty");
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

    /**
     * Pins the "omit, don't null" contract: {@code Optional<String>} unwraps to a plain,
     * <em>non-nullable</em> {@code string} schema, so under this application's
     * {@code openapi-contract} strategy the server rejects {@code "nickname": null} with a 400 at
     * the contract gate, before Jackson ever binds the body. Clients omit the property instead —
     * semantically equivalent and accepted on every path
     * (see {@link #postOptionalGreeting_nicknameOmitted()}).
     *
     * <p>The client-side {@link OpenApiValidationFilter} is bypassed so the deliberately
     * spec-invalid body actually reaches the server; with the filter on, the request is aborted
     * client-side and the server gate is never exercised.
     *
     * <p>The runtime {@code null} → {@link java.util.Optional#empty()} binding still holds on
     * non-contract-validated paths (the default {@code web-validation} strategy synthesizes schemas
     * from the Java types and does not read {@code openapi.json}; ADR-0121) — which is precisely why
     * omission, not {@code null}, is the portable form.
     */
    @Test
    @DisplayName("POST /json-demo/optional with an explicit null nickname is rejected with 400 by the contract gate")
    void postOptionalGreeting_nicknameNull() {
        given().noFiltersOfType(OpenApiValidationFilter.class)
                .contentType("application/json")
                .body("{\"name\":\"Ada\",\"nickname\":null}")
                .when()
                .post("/json-demo/optional")
                .then()
                .statusCode(400);
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

        assertFalse(body.containsKey("discount"), "discount must be omitted when empty");
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

    /**
     * Proves the <em>server-side</em> rejection of a JSON number for a {@code vertique-strict}
     * {@code BigDecimal} property. Under the {@code openapi-contract} strategy the rejecting gate is
     * the contract validator — the response is the sanitized {@code "Request validation failed"}
     * problem with {@code #/amount … "type": "type"}, not the strict deserializer's
     * {@code "Request body rejected by JSON profile"}, because the contract gate runs before the
     * body is bound. The strict deserializer remains the second line of defence on paths that do not
     * validate against the contract.
     *
     * <p>The {@link OpenApiValidationFilter} is bypassed because the generated spec declares
     * {@code amount} as a decimal string: with the filter on, this deliberately spec-invalid body is
     * aborted client-side and the server is never reached.
     */
    @Test
    @DisplayName("POST /json-demo/price with a numeric (non-string) amount is rejected with 400")
    void postPriceQuote_numericAmount_rejected() {
        given().noFiltersOfType(OpenApiValidationFilter.class)
                .contentType("application/json")
                .body("{\"sku\":\"SKU-1\",\"amount\":1.5}")
                .when()
                .post("/json-demo/price")
                .then()
                .statusCode(400);
    }

    /**
     * Proves the contract gate rejects a request body missing the required {@code sku}/{@code
     * amount} properties (400) instead of letting an empty {@link
     * dev.vertique.examples.hello.resource.JsonProfilesDemoResource.PriceQuote} reach the handler and
     * NPE while unboxing {@code amount} into a 500.
     *
     * <p>The {@link OpenApiValidationFilter} is bypassed because {@code {}} is deliberately
     * spec-invalid: with the filter on, the request is aborted client-side and the server gate is
     * never exercised.
     */
    @Test
    @DisplayName("POST /json-demo/price with an empty body is rejected with 400, not a 500")
    void postPriceQuote_emptyBody_rejected() {
        given().noFiltersOfType(OpenApiValidationFilter.class)
                .contentType("application/json")
                .body("{}")
                .when()
                .post("/json-demo/price")
                .then()
                .statusCode(400);
    }
}
