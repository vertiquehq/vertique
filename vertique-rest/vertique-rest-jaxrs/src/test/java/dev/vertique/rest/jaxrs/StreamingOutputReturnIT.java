// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.swagger.v3.oas.annotations.Operation;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.StreamingOutput;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Characterizes what a client receives when a resource method returns
 * {@link jakarta.ws.rs.core.StreamingOutput} from a {@code text/plain} method: no response body
 * encoder accepts that combination, so the request fails with a 500 problem response and the
 * written bytes are never sent. The behaviour is not claimed for other media types such as JSON.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class StreamingOutputReturnIT {

    private HttpServer server;
    private WebClient client;

    @AfterEach
    void tearDown(VertxTestContext ctx) {
        if (client != null) {
            client.close();
        }
        Future<Void> serverClose = server != null ? server.close() : Future.succeededFuture();
        serverClose.onComplete(ar -> ctx.completeNow());
    }

    /** Resource returning a {@link StreamingOutput} that writes {@code hello}. */
    @Path("/stream")
    public static class StreamingResource {

        /**
         * Returns a streaming body.
         *
         * @return an output that writes {@code hello}
         */
        @GET
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "streamHello")
        public StreamingOutput hello() {
            return out -> out.write("hello".getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    @DisplayName(
            "a StreamingOutput return from a text/plain method is not streamed: the client gets a 500 problem response")
    void streamingOutputReturnIsRefusedWithProblemResponse(Vertx vertx, VertxTestContext ctx) {
        JaxRsRouterMount mount =
                TestFactories.builder().build().create("/*", "openapi.json", Set.of(new StreamingResource()));
        mount.createRouter(vertx)
                .compose(apiRouter -> {
                    Router root = Router.router(vertx);
                    root.route("/*").subRouter(apiRouter);
                    return vertx.createHttpServer().requestHandler(root).listen(0, "127.0.0.1");
                })
                .compose(s -> {
                    server = s;
                    client = WebClient.create(vertx, new WebClientOptions().setFollowRedirects(false));
                    return client.get(s.actualPort(), "127.0.0.1", "/stream").send();
                })
                .onComplete(ctx.succeeding(resp -> {
                    ctx.verify(() -> {
                        assertEquals(500, resp.statusCode());
                        assertEquals("application/problem+json", resp.getHeader("Content-Type"));
                        assertFalse(resp.bodyAsString().contains("hello"), "the streamed bytes must not be sent");
                    });
                    ctx.completeNow();
                }));
    }
}
