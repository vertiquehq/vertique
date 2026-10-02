// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.services.codegen;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vertique.application.test.VertiqueAppExtension;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Starts the example with the {@code apidocs} section of its shipped configuration and checks that
 * no document is published while the business routes still answer.
 *
 * <p>The {@code apidocs} section is read from the classpath resource {@code config/application.json}
 * and merged with the loopback {@code http} and disabled {@code management} configuration of the
 * other integration tests; nothing else from the shipped file is used, so the test never binds a
 * non-loopback address. When the resource or its {@code apidocs} section is missing, the test class
 * fails to initialize with a message naming what is missing.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class ShippedConfigurationIT {

    private static final String SHIPPED_CONFIGURATION = "config/application.json";

    @RegisterExtension
    static final VertiqueAppExtension app = VertiqueAppExtension.forFactory(new AppComponentVertiqueComponentFactory())
            .withConfig(new JsonObject()
                    .put("http", new JsonObject().put("port", 0).put("host", "127.0.0.1"))
                    .put("management", new JsonObject().put("enabled", false))
                    .put("apidocs", shippedApiDocsSection()));

    /**
     * Resets RestAssured configuration after all tests complete.
     */
    @AfterAll
    static void tearDown() {
        RestAssured.reset();
    }

    /**
     * Requests the {@code services} document and a business route under the shipped configuration.
     */
    @Test
    @DisplayName("the shipped configuration publishes no document and the routes still answer")
    void shippedConfigurationPublishesNoDocument() {
        // Given: the example started with the shipped apidocs section
        // When: the services document is requested
        Response document =
                given().baseUri("http://127.0.0.1").port(app.httpPort()).when().get("/apidocs/services/openapi.json");

        // Then: no document is served
        assertEquals(
                404, document.statusCode(), "GET /apidocs/services/openapi.json answered " + document.statusCode());

        // When: a shipment is dispatched
        Response shipped = given().baseUri("http://127.0.0.1")
                .port(app.httpPort())
                .contentType(ContentType.JSON)
                .body("{\"orderId\":\"order-003\",\"destinationCity\":\"Tampere\"}")
                .when()
                .post("/shipping/ship");

        // Then: the business route answers as before
        assertEquals(
                200,
                shipped.statusCode(),
                "POST /shipping/ship answered " + shipped.statusCode() + ": " + shipped.asString());
        assertEquals("order-003", shipped.jsonPath().getString("orderId"));
    }

    /**
     * Reads the {@code apidocs} section of the shipped configuration from the classpath.
     *
     * @return a copy of the section
     * @throws IllegalStateException when the resource is missing or has no {@code apidocs} object
     */
    private static JsonObject shippedApiDocsSection() {
        try (InputStream in =
                ShippedConfigurationIT.class.getClassLoader().getResourceAsStream(SHIPPED_CONFIGURATION)) {
            if (in == null) {
                throw new IllegalStateException("the shipped configuration " + SHIPPED_CONFIGURATION
                        + " is not on the classpath; the example must ship it with an apidocs section");
            }
            JsonObject shipped = new JsonObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            if (!(shipped.getValue("apidocs") instanceof JsonObject section)) {
                throw new IllegalStateException(
                        "the shipped configuration " + SHIPPED_CONFIGURATION + " has no apidocs object");
            }
            return section.copy();
        } catch (IOException e) {
            throw new UncheckedIOException("reading " + SHIPPED_CONFIGURATION + " failed", e);
        }
    }
}
