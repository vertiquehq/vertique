// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.node;

import dev.vertique.examples.workflow.load.config.LoadConfig;
import dev.vertique.examples.workflow.load.workflow.LoadStart;
import dev.vertique.examples.workflow.load.workflow.LoadWorkflow;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.file.CopyOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;

/**
 * The loopback ingress of a load node: a small HTTP server that starts workflow instances for the driver.
 *
 * <p>It is harness code, not a REST layer. It exists so that a driver in another process can offer starts to a node
 * and learn, per start, whether the node accepted it or refused it.
 *
 * <table>
 *   <caption>Requests</caption>
 *   <tr><th>Request</th><th>Answer</th></tr>
 *   <tr><td>{@code POST /start?key=K}</td><td>{@code 200} {@code {"outcome":"accepted","workflowId":"..."}} when the
 *       start was recorded or the key had been started before; {@code 503} {@code {"outcome":"refused"}} when the
 *       intake limit refused it, which writes nothing and leaves the key free; {@code 500}
 *       {@code {"outcome":"failed","error":"..."}} for any other failure; {@code 400} for a missing or malformed
 *       key</td></tr>
 *   <tr><td>{@code GET /ready}</td><td>{@code 200}. The ingress deploys after the services and the executor, so an
 *       answer means the node has started</td></tr>
 *   <tr><td>{@code GET /effective}</td><td>{@code 200} with the configuration the node runs on, see
 *       {@link EffectiveConfig}</td></tr>
 * </table>
 *
 * <p>The start goes through the Dagger-wired workflow client, the same public {@code start} an application uses. The
 * ingress logs nothing per request.
 */
public final class IngressVerticle extends AbstractVerticle {

    private static final int MAX_KEY_LENGTH = 128;

    private final LoadConfig.Ingress config;
    private final LoadWorkflow workflow;
    private final EffectiveConfig effective;
    private HttpServer server;

    /**
     * Creates the ingress.
     *
     * @param config the harness settings; the listener comes from {@link LoadConfig#ingress()}
     * @param workflow the workflow client the starts go through
     * @param effective the view of the node's configuration served at {@code /effective}
     */
    @Inject
    public IngressVerticle(LoadConfig config, LoadWorkflow workflow, EffectiveConfig effective) {
        this.config = config.ingress();
        this.workflow = workflow;
        this.effective = effective;
    }

    @Override
    public void start(Promise<Void> startPromise) {
        HttpServerOptions options =
                new HttpServerOptions().setHost(config.host()).setPort(config.port());
        vertx.createHttpServer(options)
                .requestHandler(this::handle)
                .listen()
                .compose(listening -> {
                    server = listening;
                    return announce(listening.actualPort());
                })
                .onSuccess(ignored -> startPromise.complete())
                .onFailure(startPromise::fail);
    }

    @Override
    public void stop(Promise<Void> stopPromise) {
        if (server == null) {
            stopPromise.complete();
            return;
        }
        server.close().onComplete(stopPromise);
    }

    /**
     * Returns whether a string may be used as the key of a start: from 1 to 128 characters, each a letter, a digit or
     * one of {@code . _ : -}.
     *
     * @param key the value of the {@code key} parameter; may be {@code null}
     * @return {@code true} when the key is acceptable
     */
    static boolean isValidKey(String key) {
        if (key == null || key.isEmpty() || key.length() > MAX_KEY_LENGTH) {
            return false;
        }
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '.'
                    || c == '_'
                    || c == ':'
                    || c == '-';
            if (!allowed) {
                return false;
            }
        }
        return true;
    }

    private void handle(HttpServerRequest request) {
        switch (request.path()) {
            case "/start" -> {
                if (request.method() != HttpMethod.POST) {
                    reply(request, 405, new JsonObject().put("outcome", "method-not-allowed"));
                    return;
                }
                request.body().onSuccess(ignored -> start(request));
            }
            case "/ready" -> {
                if (request.method() != HttpMethod.GET) {
                    reply(request, 405, new JsonObject().put("outcome", "method-not-allowed"));
                    return;
                }
                reply(request, 200, new JsonObject().put("ready", true));
            }
            case "/effective" -> {
                if (request.method() != HttpMethod.GET) {
                    reply(request, 405, new JsonObject().put("outcome", "method-not-allowed"));
                    return;
                }
                reply(request, 200, effective.toJson());
            }
            default -> reply(request, 404, new JsonObject().put("outcome", "not-found"));
        }
    }

    private void start(HttpServerRequest request) {
        String key = request.getParam("key");
        if (!isValidKey(key)) {
            reply(request, 400, new JsonObject().put("outcome", "invalid").put("error", "key"));
            return;
        }
        workflow.start(new LoadStart(key))
                .onSuccess(id -> reply(
                        request,
                        200,
                        new JsonObject()
                                .put("outcome", "accepted")
                                .put("workflowId", id.value().toString())))
                .onFailure(failure -> {
                    if (Refusals.isIntakeRefusal(failure)) {
                        reply(request, 503, new JsonObject().put("outcome", "refused"));
                    } else {
                        reply(
                                request,
                                500,
                                new JsonObject()
                                        .put("outcome", "failed")
                                        .put("error", failure.getClass().getSimpleName()));
                    }
                });
    }

    private static void reply(HttpServerRequest request, int status, JsonObject body) {
        if (request.response().ended()) {
            return;
        }
        request.response()
                .setStatusCode(status)
                .putHeader("content-type", "application/json")
                .end(body.encode());
    }

    private Future<Void> announce(int port) {
        String target = config.portFile();
        if (target == null) {
            return Future.succeededFuture();
        }
        String temporary = target + ".tmp";
        return vertx.fileSystem()
                .writeFile(temporary, Buffer.buffer(Integer.toString(port)))
                .compose(ignored ->
                        vertx.fileSystem().move(temporary, target, new CopyOptions().setReplaceExisting(true)));
    }
}
