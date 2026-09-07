// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vertique.rest.test.RestTestContributions;
import dev.vertique.rest.test.RestTestMounts;
import io.swagger.v3.oas.annotations.Operation;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Proves that a <em>zero-config</em> JAX-RS route binds and renders a body whose components are
 * {@code Optional} and {@code java.time} values (T011/TP-002, FR-012/FR-015).
 *
 * <p>The route is mounted through {@link ValidationMountComponent}, so the default
 * {@code web-validation} gate is active, and the mount is built with an empty configuration: no
 * {@code json.jsonProfile}, no {@code jaxrs.jsonProfile}, and no {@code @JsonProfile} anywhere. That
 * is exactly the configuration a new application has, and it is the configuration in which the
 * defect this test closes appears: with the managed-edge tail floored at the process codec's raw
 * Vert.x mapper, {@code Optional<String>} and {@code LocalDate} components fail to bind at all
 * ({@code REQUIRE_HANDLERS_FOR_JAVA8_OPTIONALS} / {@code _TIMES}). Under the {@code vertique} floor
 * both bind, {@code due} renders as an ISO-8601 date, and an absent {@code text} is omitted from the
 * response rather than rendered as {@code null}.
 *
 * <p><strong>The gate is not the subject and does not stand in the way.</strong> The synthesized
 * body schema for {@code Note} is
 * {@code {"type":"object","properties":{"due":{"type":"string","format":"date"},
 * "text":{"type":["string","null"]}}}} — {@code text} is not required (victools flattens the
 * optional and marks nothing required without a nullability annotation) and both request bodies
 * validate, so a failure here is a binding or rendering failure, never a gate rejection. That was
 * verified against the real {@link AnnotationSchemaSource} before this test was written.
 *
 * <p><strong>Sensitivity.</strong> Setting {@code json.jsonProfile=system} on the mount config makes
 * the second request render {@code {"text":null,"due":"2026-09-06"}} — it still binds, but the
 * omission assertion fails, so the assertion is pinned to the {@code vertique} floor specifically and
 * not merely to "some profile that supports jsr310".
 *
 * <p><strong>Why a {@link WebClient} and not a raw {@code HttpClient}.</strong> A raw
 * {@code HttpClientResponse} discards body buffers that arrive before a body handler is attached, so
 * under load {@code body()} can succeed with zero bytes while the status code is correct (issue
 * #167). Both assertions here are byte-exact assertions on the response body, so a silently emptied
 * body would report a profile defect that did not happen. A {@link WebClient} aggregates the body
 * into its {@code HttpResponse} before completing the send.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class OptionalBodyZeroConfigIT {

    /** The request body carrying the present optional, and the exact response expected for it. */
    private static final String BODY_WITH_TEXT = "{\"text\":\"hi\",\"due\":\"2026-09-06\"}";

    /** The request body omitting the optional, and the exact response expected for it. */
    private static final String BODY_WITHOUT_TEXT = "{\"due\":\"2026-09-06\"}";

    private HttpServer server;
    private WebClient client;

    /**
     * Closes the {@link WebClient} and then the server started by the test that just ran.
     *
     * <p>{@link WebClient#close()} is {@code void}, unlike {@code HttpClient.close()}: it returns once
     * the underlying client has been asked to close, so there is no future to join here and the server
     * close alone carries the completion.
     *
     * @param ctx the test context used for async teardown assertion
     */
    @AfterEach
    void tearDown(VertxTestContext ctx) {
        if (client != null) {
            client.close();
        }
        Future<Void> serverClose = server != null ? server.close() : Future.succeededFuture();
        serverClose.onComplete(ar -> ctx.completeNow());
    }

    // --- Resource fixture ---

    /**
     * A body record with one {@code Optional} component and one {@code java.time} component — the two
     * shapes the raw Vert.x mapper cannot handle in either direction.
     *
     * @param text an optional free-text note
     * @param due  the ISO-8601 date the note is due
     */
    public record Note(Optional<String> text, LocalDate due) {}

    /** Zero-config resource: no {@code @JsonProfile} at method or class level. */
    @Path("/echo")
    public static class NoteResource {

        /**
         * Echoes the posted note back, so one request exercises both the binding and the rendering
         * direction of the effective profile.
         *
         * @param note the bound request body
         * @return the same note, rendered by the effective profile
         */
        @POST
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.APPLICATION_JSON)
        @Operation(operationId = "echoNote")
        public Note echo(Note note) {
            return note;
        }
    }

    // --- Test ---

    @Test
    @DisplayName("A zero-config route binds and renders Optional and LocalDate body components")
    void optionalAndDateComponentsBindAndRender(Vertx vertx, VertxTestContext ctx) {
        RestTestMounts.startServer(
                        vertx,
                        MountFixtures.mount(
                                vertx, RestTestContributions.builder().build()),
                        Set.of(new NoteResource()))
                .onComplete(ctx.succeeding(started -> {
                    server = started;
                    // Redirects off: parity with the raw client; WebClient forwards Authorization across 3xx.
                    client = WebClient.create(vertx, new WebClientOptions().setFollowRedirects(false));
                    int port = started.actualPort();
                    post(port, BODY_WITH_TEXT)
                            .compose(withText ->
                                    post(port, BODY_WITHOUT_TEXT).map(withoutText -> List.of(withText, withoutText)))
                            .onComplete(ctx.succeeding(responses -> {
                                ctx.verify(() -> {
                                    assertEquals(
                                            "200|" + BODY_WITH_TEXT,
                                            responses.get(0),
                                            "a present Optional and a LocalDate must bind and render round-trip");
                                    assertEquals(
                                            "200|" + BODY_WITHOUT_TEXT,
                                            responses.get(1),
                                            "an absent Optional must bind and be omitted from the response");
                                });
                                ctx.completeNow();
                            }));
                }));
    }

    // --- Helpers ---

    /**
     * POSTs {@code body} as {@code application/json} to {@code /echo} and pairs the response status
     * with the response body, so an unexpected status and an unexpected body are both visible in one
     * assertion message.
     *
     * @param port the bound server port
     * @param body the raw request body
     * @return a future of {@code "<status>|<body>"}
     */
    private Future<String> post(int port, String body) {
        return client.post(port, "127.0.0.1", "/echo")
                .putHeader("Content-Type", MediaType.APPLICATION_JSON)
                .sendBuffer(Buffer.buffer(body))
                .map(response -> response.statusCode() + "|" + response.bodyAsString());
    }
}
