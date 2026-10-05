// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vertique.rest.core.ProblemDetail;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Real HTTP regression for a failure cycle that reuses the same Throwable after {@code reroute()}.
 *
 * <p>{@code fail(400, sharedCause)} is observed and handed to {@link ErrorPipeline#mapToResponse},
 * which consumes the primary hint and the observation pair. The first mapped response is not sent —
 * the context is {@code reroute()}d and the next route calls {@code fail(401, sharedCause)}. The
 * second cycle must answer 401. Without observation consume (or with an identity-only status-only
 * heuristic), the observer freezes at 400 and the terminal stash rewrites the new 401 outcome.
 *
 * <p>This is deliberately a thin Vert.x router rather than a full {@link JaxRsRouterMount}: the bug
 * is in the observe/consume handoff, and mounting the whole JAX-RS stack would not make the reroute
 * boundary sharper. The mounted observer sitting adjacent to the terminal handler still cannot see a
 * status rewrite that an upstream failure handler applies before either runs — that case is not
 * claimed here.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class VertxFailureStatusRerouteIT {

    private static final RuntimeException SHARED_CAUSE = new RuntimeException("shared");

    private static final String REROUTED_KEY = "dev.vertique.rest.jaxrs.test.rerouted";

    private static final long ASYNC_TIMEOUT_SECONDS = 10;

    private static HttpServer server;
    private static WebClient client;
    private static ErrorPipeline errorPipeline;

    @BeforeAll
    static void setUp(Vertx vertx, VertxTestContext ctx) {
        DefaultExceptionMapper defaults = new DefaultExceptionMapper().on(Throwable.class, ex -> Response.status(500)
                .entity(ProblemDetail.of(500, "Internal Server Error"))
                .type("application/problem+json")
                .build());
        errorPipeline = new ErrorPipeline(
                List.of(), List.of(), new RestExceptionMapper(), new ExceptionMapperRegistry(defaults, Set.of()));

        Router router = Router.router(vertx);
        router.get("/start").handler(rc -> rc.fail(400, SHARED_CAUSE));
        router.get("/second").handler(rc -> rc.fail(401, SHARED_CAUSE));

        router.route().failureHandler(rc -> {
            VertxFailureStatus.observeFailurePair(rc);
            rc.next();
        });
        router.route().failureHandler(rc -> {
            Throwable cause = rc.failure();
            Integer clientError = VertxFailureStatus.clientErrorStatusForCause(rc);
            if (clientError != null) {
                rc.data().put(VertxFailureStatus.KEY, clientError);
            }
            if (!Boolean.TRUE.equals(rc.get(REROUTED_KEY))) {
                rc.put(REROUTED_KEY, true);
                // Hand the first failure to mapping so KEY + observation are consumed, then reroute
                // instead of writing the mapped body — the regression is the second cycle's status.
                errorPipeline
                        .mapToResponse(rc, cause)
                        .onSuccess(ignored -> rc.reroute("/second"))
                        .onFailure(err -> rc.response().setStatusCode(500).end(err.getMessage()));
                return;
            }
            errorPipeline
                    .mapToResponse(rc, cause)
                    .onSuccess(response -> rc.response()
                            .setStatusCode(response.getStatus())
                            .putHeader("X-Observed-Hint", String.valueOf(clientError))
                            .end())
                    .onFailure(err -> rc.response().setStatusCode(500).end(err.getMessage()));
        });

        vertx.createHttpServer().requestHandler(router).listen(0, "127.0.0.1").onComplete(ctx.succeeding(listening -> {
            server = listening;
            client = WebClient.create(vertx, new WebClientOptions().setFollowRedirects(false));
            ctx.completeNow();
        }));
    }

    @AfterAll
    static void tearDown(VertxTestContext ctx) {
        if (client != null) {
            client.close();
        }
        Future<Void> serverClose = server != null ? server.close() : Future.succeededFuture();
        serverClose.onComplete(ctx.succeeding(v -> ctx.completeNow()));
    }

    @Test
    @DisplayName("After consume + reroute, fail(401, sharedCause) answers 401 — not a frozen 400")
    void reusedCauseAfterRerouteAnswersNewStatus() throws Exception {
        var response = client.get(server.actualPort(), "127.0.0.1", "/start")
                .send()
                .toCompletionStage()
                .toCompletableFuture()
                .get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(
                401,
                response.statusCode(),
                "second-cycle fail(401, sharedCause) must win; observer hint was "
                        + response.getHeader("X-Observed-Hint"));
        assertEquals(
                "401",
                response.getHeader("X-Observed-Hint"),
                "clientErrorStatusForCause must expose 401 after observation was consumed on the first cycle");
    }
}
