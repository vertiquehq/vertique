// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.auth.jwt;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vertique.rest.core.security.RouteAuthHandler;
import dev.vertique.rest.security.CredentialRejectionReporter;
import dev.vertique.rest.security.RestAuthenticationEvidence;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

@ExtendWith(VertxExtension.class)
class JwtOptionalRouteAuthIT {

    private static final String KEY = "test-secret-key-with-at-least-256-bits-of-padding-for-hs256-signing";

    private HttpServer server;
    private WebClient client;

    @AfterEach
    void close(VertxTestContext testContext) {
        if (client != null) {
            client.close();
        }
        (server == null ? Future.<Void>succeededFuture() : server.close())
                .onComplete(testContext.succeeding(ignored -> testContext.completeNow()));
    }

    @Test
    void shouldDistinguishAbsentValidAndInvalidBearerCredentials(Vertx vertx, VertxTestContext testContext) {
        JWTAuth jwtAuth = JwtAuthFactory.fromSymmetricKey(vertx, "HS256", KEY);
        CredentialRejectionReporter rejections = Mockito.mock(CredentialRejectionReporter.class);
        RouteAuthHandler routeAuth = JwtAuthModule.jwtRouteAuthHandler(
                jwtAuth,
                new JwtAuthConfig("bearerAuth", JwtValidationConfig.builder().build()),
                rejections);
        AtomicInteger continuations = new AtomicInteger();

        Router router = Router.router(vertx);
        router.route("/optional").handler(routeAuth.createOptionalHandler().orElseThrow());
        router.route("/optional").handler(context -> {
            continuations.incrementAndGet();
            String subject = context.user() == null
                    ? "anonymous"
                    : context.user().principal().getString("sub");
            context.response()
                    .setStatusCode(200)
                    .end(subject + ":" + RestAuthenticationEvidence.get(context).size());
        });
        router.route()
                .failureHandler(context ->
                        context.response().setStatusCode(context.statusCode()).end());

        client = WebClient.create(vertx, new WebClientOptions().setFollowRedirects(false));
        String validToken = jwtAuth.generateToken(new JsonObject().put("sub", "alice"));
        String tamperedToken = validToken + "x";

        vertx.createHttpServer()
                .requestHandler(router)
                .listen(0, "127.0.0.1")
                .compose(httpServer -> {
                    server = httpServer;
                    return client.get(httpServer.actualPort(), "127.0.0.1", "/optional")
                            .send();
                })
                .compose(absent -> {
                    assertEquals(200, absent.statusCode());
                    assertEquals("anonymous:0", absent.bodyAsString());
                    return client.get(server.actualPort(), "127.0.0.1", "/optional")
                            .putHeader("Authorization", "Bearer " + validToken)
                            .send();
                })
                .compose(valid -> {
                    assertEquals(200, valid.statusCode());
                    assertEquals("alice:1", valid.bodyAsString());
                    return client.get(server.actualPort(), "127.0.0.1", "/optional")
                            .putHeader("Authorization", "Bearer " + tamperedToken)
                            .send();
                })
                .onComplete(testContext.succeeding(invalid -> {
                    assertEquals(401, invalid.statusCode());
                    assertEquals(2, continuations.get());
                    testContext.completeNow();
                }));
    }
}
