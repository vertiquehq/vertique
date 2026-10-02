// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.vertique.rest.openapi.docs.PatternBoundTestComponents.BoundComponent;
import dev.vertique.rest.openapi.docs.fixture.DocsConfigs;
import dev.vertique.rest.openapi.docs.fixture.conformance.bound.BoundApi;
import dev.vertique.rest.openapi.docs.fixture.conformance.bound.BoundResource;
import dev.vertique.rest.openapi.docs.fixture.support.Deployments;
import dev.vertique.rest.openapi.docs.fixture.support.DocumentRequests;
import dev.vertique.rest.openapi.docs.fixture.support.Futures;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;

/**
 * Proves how the {@code web-validation} gate counts characters at pattern positions against the
 * per-request total (default 262,144) when a body type reserves a name: each extra key then counts
 * three times, not twice.
 *
 * <p>Every body has one published member {@code label} (5 characters, no pattern, no bounded format)
 * and {@code n} extra keys of exactly 4,000 characters each, each with the value 1. Keys are below the
 * per-input limit (4,096), so only the total can reject. The arithmetic of each row:
 *
 * <ul>
 *   <li>{@code /reserved} counts each key three times (two {@code pattern} nodes under the property
 *       names and the pattern-properties entry): {@code 3 x (5 + 21 x 4000) = 252,015} is within
 *       262,144 and accepted for 21 keys; 22 keys give {@code 264,015} and 30 give {@code 360,015},
 *       both rejected.
 *   <li>{@code /plain} counts each key twice: 30 keys give {@code 240,010} and 32 give
 *       {@code 256,010}, both accepted; 33 give {@code 264,010}, rejected.
 *   <li>{@code /nested} wraps the {@code /reserved} body as {@code inner}: the holder's key
 *       {@code inner} counts {@code 3 x 5 = 15} and the inner body counts once per level, so 21 keys
 *       give {@code 252,030} (accepted) and 22 give {@code 264,030} (rejected).
 * </ul>
 *
 * <p>The 30-key pair is the separated-guard statement: the same 30 keys fit when no name is reserved
 * and do not fit when one is, because the reserved name adds a third counted position per key.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
public class PatternBoundAccountingIT {

    /** The longest one deployment, request, or undeployment is awaited. */
    private static final Duration WAIT = Duration.ofSeconds(5);

    private static final int NO_CONTENT = 204;
    private static final int BAD_REQUEST = 400;
    private static final int KEY_LENGTH = 4_000;
    private static final int DEFAULT_MAX_TOTAL_CHARS = 262_144;
    private static final String TOTAL_TYPE = "patternInputTotalLength";

    private final List<String> deploymentIds = new ArrayList<>();
    private Vertx vertx;
    private WebClient client;
    private int port;

    /** One request: the route, the number of extra keys, and whether the gate accepts it. */
    private record Row(String route, int keys, boolean accepted) {}

    @BeforeEach
    void deploy(Vertx sharedVertx) throws Exception {
        vertx = sharedVertx;
        client = DocumentRequests.separateConnectionsClient(vertx);
        JsonObject config = DocsConfigs.loopback();
        config.getJsonObject("jaxrs").put("validationStrategy", "web-validation");
        BoundComponent component =
                DaggerPatternBoundTestComponents_BoundComponent.factory().create(vertx, config);
        port = Deployments.deployAndReadPort(
                vertx, component::httpVerticle, new DeploymentOptions(), deploymentIds, WAIT);
    }

    @AfterEach
    void cleanUp() throws Exception {
        try {
            Deployments.undeployAll(vertx, deploymentIds, WAIT);
        } finally {
            deploymentIds.clear();
            client.close();
        }
    }

    @Test
    @DisplayName(
            "Extra keys of a body that reserves a name count three times against the pattern total, and two times when no name is reserved")
    void separatedGuardKeysCountThreeTimesNearTheTotal() throws Exception {
        // Given: the three routes and the rows of the arithmetic in the class Javadoc.
        List<Row> rows = List.of(
                new Row(BoundResource.RESERVED, 21, true),
                new Row(BoundResource.RESERVED, 22, false),
                new Row(BoundResource.RESERVED, 30, false),
                // The separated-guard statement: the same 30 keys fit without a reserved name (here) and do not
                // fit with one (the /reserved row above).
                new Row(BoundResource.PLAIN, 30, true),
                new Row(BoundResource.PLAIN, 32, true),
                new Row(BoundResource.PLAIN, 33, false),
                new Row(BoundResource.NESTED, 21, true),
                new Row(BoundResource.NESTED, 22, false));

        List<Executable> checks = new ArrayList<>();
        for (Row row : rows) {
            // When: the body is posted.
            JsonObject body = body(row.keys());
            JsonObject sent = BoundResource.NESTED.equals(row.route()) ? new JsonObject().put("inner", body) : body;
            HttpResponse<Buffer> response = Futures.await(
                    client.post(port, "127.0.0.1", BoundApi.PATH + row.route())
                            .putHeader("Content-Type", "application/json")
                            .sendBuffer(sent.toBuffer()),
                    WAIT);
            String answer = response.bodyAsString() == null ? "" : response.bodyAsString();
            String label = row.route() + " with " + row.keys() + " keys";
            int status = response.statusCode();

            // Then: it is accepted or rejected on the total with one value-free detail.
            if (row.accepted()) {
                checks.add(() ->
                        assertEquals(NO_CONTENT, status, label + " must be accepted; body: " + abbreviate(answer)));
            } else {
                checks.add(() -> assertRejectedOnTotal(label, status, answer));
            }
        }
        assertAll("every row", checks);
    }

    private static void assertRejectedOnTotal(String label, int status, String answer) {
        JsonArray errors;
        try {
            errors = new JsonObject(answer).getJsonArray("errors");
        } catch (RuntimeException notAProblemBody) {
            errors = null;
        }
        JsonArray found = errors;
        assertAll(
                label + " must be rejected on the total",
                () -> assertEquals(BAD_REQUEST, status, "status; body: " + abbreviate(answer)),
                () -> assertEquals(
                        1, found == null ? -1 : found.size(), "exactly one detail; body: " + abbreviate(answer)),
                () -> {
                    JsonObject detail = found.getJsonObject(0);
                    assertEquals(TOTAL_TYPE, detail.getString("type"), "type");
                    assertEquals("body", detail.getString("location"), "location");
                    assertEquals("", detail.getString("path"), "path");
                    assertEquals(
                            new JsonObject().put("maxTotalChars", DEFAULT_MAX_TOTAL_CHARS),
                            detail.getJsonObject("args"),
                            "args");
                },
                () -> assertFalse(answer.contains(key(0)), "a whole key leaked: " + abbreviate(answer)),
                () -> assertFalse(
                        answer.contains(key(0).substring(0, 20)), "a key prefix leaked: " + abbreviate(answer)),
                () -> assertFalse(answer.contains("k".repeat(20)), "a key run leaked: " + abbreviate(answer)));
    }

    /** A body with {@code label} and {@code keys} extra keys of 4,000 characters, each with the value 1. */
    private static JsonObject body(int keys) {
        JsonObject body = new JsonObject().put("label", "x");
        for (int i = 0; i < keys; i++) {
            body.put(key(i), 1);
        }
        return body;
    }

    /** The {@code i}th key: a six-digit index padded with the letter {@code k} to 4,000 characters. */
    private static String key(int i) {
        return String.format("%06d", i) + "k".repeat(KEY_LENGTH - 6);
    }

    private static String abbreviate(String body) {
        return body.length() > 600 ? body.substring(0, 600) + "...(" + body.length() + " chars)" : body;
    }
}
