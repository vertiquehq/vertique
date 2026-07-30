// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.rest.test.RestTestContributions;
import dev.vertique.rest.test.RestTestMounts;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * End-to-end tests for the default {@code web-validation} gate wired into a plain-{@link
 * io.vertx.ext.web.Router} {@code JaxRsRouterMount} (PRD-REST-017 slice 9). These build a real mount
 * through {@link ValidationMountComponent} (the {@code vertique-rest-test} fixture), so the graph
 * carries the real {@link WebValidationStrategy} (selected by id {@code "web-validation"}, the
 * framework default), the victools-backed {@link AnnotationSchemaSource}, and the production
 * {@code RestValidationException} &rarr; 400 mapping already wired into {@code RestModule}'s
 * {@code DefaultExceptionMapper} — no test-local mirror of that rule is needed. Driving HTTP requests
 * against it proves:
 *
 * <ul>
 *   <li>a body violating a {@code minLength} constraint yields a 400 {@code application/problem+json}
 *       and the resource method is never invoked;</li>
 *   <li>repeated header values for a {@code List<String>} header param bind end-to-end under the gate
 *       (the all-values rule), proving binding still runs after the gate.</li>
 * </ul>
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class WebValidationGateIT {

    private HttpServer server;
    private HttpClient client;

    @AfterEach
    void tearDown(VertxTestContext ctx) {
        Future<?> serverClose = server != null ? server.close() : Future.succeededFuture();
        Future<?> clientClose = client != null ? client.close() : Future.succeededFuture();
        Future.join(serverClose, clientClose).onComplete(ar -> ctx.completeNow());
    }

    // --- Test 4: web-validation gate rejects a body violating minLength ---

    /** Body bean whose {@code name} carries a {@code minLength: 3} schema constraint. */
    public static class CreateRequest {
        @Schema(minLength = 3)
        public String name;
    }

    /** Resource whose POST records whether it was invoked (to prove the gate blocks it). */
    @Path("/create")
    public static class CreateResource {
        private final AtomicBoolean invoked;

        CreateResource(AtomicBoolean invoked) {
            this.invoked = invoked;
        }

        /**
         * Records invocation and echoes the name. Should NOT run for an invalid body.
         *
         * @param request the request body bean
         * @return the echoed name
         */
        @POST
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "create")
        public String create(CreateRequest request) {
            invoked.set(true);
            return "name=" + request.name;
        }
    }

    @Test
    @DisplayName("web-validation gate returns 400 problem+json for a minLength violation; resource not invoked")
    void webValidationDefaultGateIsInstalled(Vertx vertx, VertxTestContext ctx) {
        AtomicBoolean invoked = new AtomicBoolean(false);
        RestTestMounts.startServer(
                        vertx,
                        MountFixtures.factory(vertx, RestTestContributions.none()),
                        Set.of(new CreateResource(invoked)))
                .onComplete(ctx.succeeding(s -> {
                    server = s;
                    client = vertx.createHttpClient();
                    client.request(HttpMethod.POST, s.actualPort(), "localhost", "/create")
                            .compose(req -> req.putHeader("Content-Type", "application/json")
                                    .send("{\"name\":\"AB\"}"))
                            .compose(resp -> resp.body().map(b ->
                                    new Object[] {resp.statusCode(), resp.getHeader("Content-Type"), b.toString()}))
                            .onComplete(ctx.succeeding(arr -> {
                                ctx.verify(() -> {
                                    assertEquals(400, arr[0], "minLength violation must be a 400");
                                    assertTrue(
                                            ((String) arr[1]).contains("application/problem+json"),
                                            "error body must be problem+json; was " + arr[1]);
                                    assertFalse(
                                            invoked.get(), "resource method must not be invoked when the gate fails");
                                });
                                ctx.completeNow();
                            }));
                }));
    }

    // --- Test 14: repeated header values bind under the web-validation gate ---

    /** Resource binding a repeated header into a {@code List<String>} under the gate. */
    @Path("/tags")
    public static class TagsResource {

        /**
         * Echoes the bound list of repeated {@code X-Tag} headers.
         *
         * @param tags the repeated header values
         * @return the bound list echoed back
         */
        @GET
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "tags")
        public String tags(@HeaderParam("X-Tag") List<String> tags) {
            return "tags=" + tags;
        }
    }

    @Test
    @DisplayName("Repeated header values bind to a List end-to-end under the web-validation gate")
    void repeatedHeaderValuesCollectionParamUnderWebValidation(Vertx vertx, VertxTestContext ctx) {
        RestTestMounts.startServer(
                        vertx, MountFixtures.factory(vertx, RestTestContributions.none()), Set.of(new TagsResource()))
                .onComplete(ctx.succeeding(s -> {
                    server = s;
                    client = vertx.createHttpClient();
                    client.request(HttpMethod.GET, s.actualPort(), "localhost", "/tags")
                            .compose(req -> {
                                req.headers().add("X-Tag", "a");
                                req.headers().add("X-Tag", "b");
                                return req.send();
                            })
                            .compose(resp -> resp.body())
                            .onComplete(ctx.succeeding(body -> {
                                ctx.verify(() -> assertEquals("tags=[a, b]", body.toString()));
                                ctx.completeNow();
                            }));
                }));
    }
}
