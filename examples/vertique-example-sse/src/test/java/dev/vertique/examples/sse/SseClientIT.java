// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.sse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.application.test.VertiqueAppExtension;
import dev.vertique.http.client.CallOptions;
import dev.vertique.http.client.HttpClient;
import dev.vertique.http.client.HttpClientFactory;
import io.restassured.RestAssured;
import io.restassured.filter.log.RequestLoggingFilter;
import io.restassured.filter.log.ResponseLoggingFilter;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.codec.SseEvent;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.ArrayList;
import java.util.Collections;
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
 * Consumes the example's own server-sent event feed through a governed client obtained from the
 * application's {@link HttpClientFactory}, configured from {@code httpClient.{name}}.
 *
 * <p>Proves FR-012 (supporting): the stream arrives event by event through {@code stream(...)} and
 * {@code events()}, and {@code completion()} succeeds once the server ends the feed. No raw Vert.x
 * client and no {@code BodyCodec.sseStream}: the governed client is the only consumer under test.
 * The application binds {@code 127.0.0.1} on a dynamic port, so the request carries an absolute
 * URI built from the bound port after startup; the factory client is closed by the application's
 * shutdown step.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
@ExtendWith(VertxExtension.class)
public class SseClientIT {

    private static final String CLIENT_NAME = "jobs";

    @RegisterExtension
    static final VertiqueAppExtension app = VertiqueAppExtension.forFactory(new AppComponentVertiqueComponentFactory())
            .withConfig(new JsonObject()
                    .put("http", new JsonObject().put("port", 0).put("host", "127.0.0.1"))
                    .put("management", new JsonObject().put("enabled", false))
                    .put("job", new JsonObject().put("stepIntervalMs", 20).put("stepCount", 4))
                    .put(
                            "httpClient",
                            new JsonObject().put(CLIENT_NAME, new JsonObject().put("readTimeoutMs", 15_000))));

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
    @DisplayName("A factory client consumes the job progress stream event by event, then completes")
    void factoryClientConsumesTheJobProgressStreamEventByEventThenCompletes(VertxTestContext ctx) throws Exception {
        // Given a job and the factory client configured from httpClient.jobs
        String jobId = RestAssured.given()
                .contentType("application/json")
                .when()
                .post("/jobs")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getString("id");
        AppComponent component = app.component();
        HttpClientFactory factory = component.httpClientFactory();
        HttpClient client = factory.client(CLIENT_NAME);
        String url = "http://127.0.0.1:" + app.httpPort() + "/jobs/" + jobId + "/events";
        List<SseEvent> received = Collections.synchronizedList(new ArrayList<>());

        // When the stream is opened and its events are consumed as they arrive
        client.stream(CallOptions.defaults(), web -> web.getAbs(url).putHeader("Accept", "text/event-stream"), null)
                .onComplete(ctx.succeeding(stream -> {
                    ctx.verify(() -> {
                        assertEquals(200, stream.head().statusCode());
                        String contentType = stream.head().headers().get("Content-Type");
                        assertTrue(
                                contentType != null && contentType.startsWith("text/event-stream"),
                                "Expected an event stream, got: " + contentType);
                    });
                    stream.events().handler(received::add);
                    stream.completion().onComplete(ctx.succeeding(v -> {
                        // Then the events arrived in order and completion succeeded after the last one
                        ctx.verify(() -> {
                            List<SseEvent> events = List.copyOf(received);
                            assertTrue(events.size() >= 4, "Expected >=3 progress events and a complete event");
                            SseEvent last = events.get(events.size() - 1);
                            assertEquals("complete", last.event());
                            assertEquals("done", last.data());
                            List<SseEvent> progress = events.subList(0, events.size() - 1);
                            int lastId = -1;
                            for (SseEvent event : progress) {
                                assertEquals("progress", event.event());
                                int id = Integer.parseInt(event.id());
                                assertTrue(id > lastId, "ids should be strictly increasing");
                                assertFalse(event.data().isBlank());
                                lastId = id;
                            }
                        });
                        ctx.completeNow();
                    }));
                }));

        assertTrue(ctx.awaitCompletion(15, TimeUnit.SECONDS), "stream did not complete");
        if (ctx.failed()) {
            throw new AssertionError(ctx.causeOfFailure());
        }
    }
}
