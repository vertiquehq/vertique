// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.restclient;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import lombok.extern.slf4j.Slf4j;

/**
 * Simple HTTP server verticle that simulates a user service API.
 *
 * <p>Exposes the following endpoints:
 * <ul>
 *   <li>{@code GET  /api/users}       — returns a JSON array of two hard-coded users</li>
 *   <li>{@code GET  /api/users/:id}   — returns the user with the given ID, or 404</li>
 *   <li>{@code POST /api/users}       — accepts a JSON body, assigns a generated ID, returns 201</li>
 *   <li>{@code DELETE /api/users/:id} — always returns 204 No Content</li>
 * </ul>
 *
 * <p>The port is read from {@link MockConfig#port()} supplied at construction. This verticle is a
 * test fixture: it is stood up by {@code UserClientIT} on a separate {@link io.vertx.core.Vertx}
 * instance to back the {@code UserClient} the example application exercises — it is not part of the
 * application's Dagger graph.
 *
 * <p>The server binds to IPv4 loopback ({@code 127.0.0.1}) only — it is test support and must never
 * be remotely reachable.
 */
@Slf4j
public class MockServerVerticle extends AbstractVerticle {

    // --- Pre-defined users for GET responses ---
    private static final JsonObject USER_1 =
            new JsonObject().put("id", "1").put("name", "Alice").put("email", "alice@example.com");

    private static final JsonObject USER_2 =
            new JsonObject().put("id", "2").put("name", "Bob").put("email", "bob@example.com");

    private final int port;
    private volatile HttpServer httpServer;

    /**
     * Creates a new mock server verticle.
     *
     * @param config the mock server configuration; {@link MockConfig#port()} is the TCP port to
     *     listen on — pass {@code 0} to let the OS pick an ephemeral port (the actual bound port is
     *     then available via {@link #actualPort()} after deployment)
     */
    public MockServerVerticle(MockConfig config) {
        this.port = config.port();
    }

    /**
     * Returns the TCP port the mock server is actually listening on. Only valid after the verticle
     * has been deployed (i.e., {@link #start(Promise)} has completed); otherwise returns {@code 0}.
     *
     * <p>When the configured port is {@code 0} (ephemeral OS-allocated port), this method is the
     * only way to learn the bound port. {@code UserClientIT} reads it after deployment to construct
     * the {@code restClient.userService.baseUrl} the example application's {@code UserClient}
     * resolves, eliminating the {@code ServerSocket(0)}-style TOCTOU race a fixed-port setup has.
     *
     * @return the actual bound port, or {@code 0} if the server is not yet listening
     */
    public int actualPort() {
        return httpServer != null ? httpServer.actualPort() : 0;
    }

    @Override
    public void start(Promise<Void> startPromise) {
        Router router = Router.router(vertx);
        router.route().handler(BodyHandler.create());

        // GET /api/users — list all users
        router.get("/api/users").handler(ctx -> {
            JsonArray users = new JsonArray().add(USER_1).add(USER_2);
            ctx.response().putHeader("Content-Type", "application/json").end(users.encode());
        });

        // GET /api/users/:id — find user by id
        router.get("/api/users/:id").handler(ctx -> {
            String id = ctx.pathParam("id");
            JsonObject user = resolveUser(id);
            if (user != null) {
                ctx.response().putHeader("Content-Type", "application/json").end(user.encode());
            } else {
                ctx.response()
                        .setStatusCode(404)
                        .putHeader("Content-Type", "application/problem+json")
                        .end(new JsonObject()
                                .put("type", "about:blank")
                                .put("title", "Not Found")
                                .put("status", 404)
                                .put("detail", "User with id '" + id + "' not found")
                                .encode());
            }
        });

        // POST /api/users — create a new user
        router.post("/api/users").handler(ctx -> {
            JsonObject body = ctx.body().asJsonObject();
            String generatedId = String.valueOf(System.currentTimeMillis() % 10000);
            JsonObject created = body.copy().put("id", generatedId);
            ctx.response()
                    .setStatusCode(201)
                    .putHeader("Content-Type", "application/json")
                    .end(created.encode());
        });

        // DELETE /api/users/:id — always succeeds with 204
        router.delete("/api/users/:id").handler(ctx -> {
            ctx.response().setStatusCode(204).end();
        });

        vertx.createHttpServer()
                .requestHandler(router)
                .listen(port, "127.0.0.1")
                .onSuccess(server -> {
                    httpServer = server;
                    log.info("Mock server listening on port {}", server.actualPort());
                    startPromise.complete();
                })
                .onFailure(startPromise::fail);
    }

    /**
     * Resolves a user by ID from the pre-defined set.
     *
     * @param id the user identifier
     * @return the matching user JsonObject, or {@code null} if not found
     */
    private static JsonObject resolveUser(String id) {
        if ("1".equals(id)) return USER_1;
        if ("2".equals(id)) return USER_2;
        return null;
    }
}
