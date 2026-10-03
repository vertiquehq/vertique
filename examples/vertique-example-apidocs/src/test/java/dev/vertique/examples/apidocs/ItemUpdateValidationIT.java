// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.apidocs;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.application.test.VertiqueAppExtension;
import dev.vertique.rest.auth.jwt.JwtAuthFactory;
import io.restassured.RestAssured;
import io.restassured.filter.log.RequestLoggingFilter;
import io.restassured.filter.log.ResponseLoggingFilter;
import io.restassured.response.Response;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.jwt.JWTAuth;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Renames a catalog item through the management application as an {@code admin}, and checks that
 * the request-validation gate holds the update's name to what {@code ItemUpdate} promises: a name
 * that is present must be 1 to 64 letters, digits, or spaces, with no trailing line terminator.
 *
 * <p>Every request carries an {@code admin} token, so authentication and authorization never decide
 * the status. The example starts with its shipped configuration plus the test-only HTTP binding and
 * JWT key of {@link TestConfiguration}. Every expected value is a literal written here, never read
 * from the code under test.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class ItemUpdateValidationIT {

    /** The update operation's path in the management application, mounted at {@code /api/mgmt}. */
    private static final String ITEM_42 = "/api/mgmt/items/42";

    private static final ObjectMapper JSON = new ObjectMapper();

    @RegisterExtension
    static final VertiqueAppExtension app = VertiqueAppExtension.forFactory(new AppComponentVertiqueComponentFactory())
            .withConfig(TestConfiguration.forTest());

    private static JWTAuth tokens;

    /** Configures RestAssured and the token issuer that shares the test-only key. */
    @BeforeAll
    static void setUp() {
        RestAssured.baseURI = "http://127.0.0.1";
        RestAssured.port = app.httpPort();
        RestAssured.filters(new RequestLoggingFilter(), new ResponseLoggingFilter());
        tokens = JwtAuthFactory.fromSymmetricKey(app.vertx(), "HS256", TestConfiguration.JWT_KEY);
    }

    /** Resets RestAssured configuration after all tests complete. */
    @AfterAll
    static void tearDown() {
        RestAssured.reset();
    }

    @Test
    @DisplayName("an admin renames an item to letters, digits, and a space")
    void adminRenamesItemToAllowedName() throws IOException {
        // Given: an admin token and a name of letters, digits, and spaces
        String ada = token("ada", "admin");

        // When: the admin sends the update
        Response response = update(ada, "Desk lamp 2");

        // Then: the item is returned with the new name
        String body = response.asString();
        assertEquals(200, response.statusCode(), "status; body: " + body);
        assertEquals(JSON.readTree("{\"id\":\"42\",\"name\":\"Desk lamp 2\"}"), JSON.readTree(body), "updated item");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("disallowedNames")
    @DisplayName("an update whose name is not 1 to 64 letters, digits, or spaces is rejected")
    void updateWithDisallowedNameIsRejected(String name) throws IOException {
        // Given: an admin token, so only the name can decide the outcome
        String ada = token("ada", "admin");

        // When: the admin sends the update with the disallowed name
        Response response = update(ada, name);

        // Then: the gate rejects it as a validation problem on the name's pattern
        String body = response.asString();
        assertEquals(400, response.statusCode(), "status; body: " + body);
        JsonNode problem = JSON.readTree(body);
        assertAll(
                () -> assertEquals(
                        "application/problem+json",
                        String.valueOf(response.header("Content-Type"))
                                .split(";")[0]
                                .trim(),
                        "Content-Type"),
                () -> assertEquals("about:blank", problem.path("type").asText(), "problem type"),
                () -> assertEquals("Bad Request", problem.path("title").asText(), "problem title"),
                () -> assertEquals(400, problem.path("status").asInt(), "problem status"),
                () -> assertTrue(
                        StreamSupport.stream(problem.path("errors").spliterator(), false)
                                .anyMatch(error ->
                                        "pattern".equals(error.path("type").asText())),
                        "errors must contain a 'pattern' violation: " + problem.path("errors")));
    }

    /** Names the update's pattern must refuse, each with a display name that does not echo it. */
    static Stream<Arguments> disallowedNames() {
        return Stream.of(
                Arguments.of(Named.of("allowed characters followed by punctuation", "ok!!")),
                Arguments.of(Named.of("markup with one trailing letter", "<script>x")),
                Arguments.of(Named.of("65 letters, one over the length bound", "A".repeat(65))),
                Arguments.of(Named.of("allowed characters followed by a line feed", "Desk lamp\n")),
                Arguments.of(Named.of("allowed characters followed by CR LF", "Desk lamp\r\n")));
    }

    /**
     * Sends the update of item {@code 42} as JSON.
     *
     * @param token the bearer token
     * @param name  the new name
     * @return the answer
     */
    private static Response update(String token, String name) {
        return given().header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .body(new JsonObject().put("name", name).encode())
                .when()
                .put(ITEM_42);
    }

    /**
     * Mints a token with the test-only key.
     *
     * @param subject the token's subject
     * @param role    the single entry of its {@code roles} claim
     * @return the signed token
     */
    private static String token(String subject, String role) {
        return tokens.generateToken(new JsonObject().put("sub", subject).put("roles", new JsonArray().add(role)));
    }
}
