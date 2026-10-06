// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import dev.vertique.rest.core.router.HttpVerticle;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.Verticle;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * One deployed composition under test: its own {@link Vertx}, its own loopback server on an
 * ephemeral port, and one client that does not follow redirects. Closing it closes the client, then
 * the server, then the {@link Vertx} it owns, whatever failed before, so a test never leaves a
 * socket or an event loop behind and a failing request cannot mask the next test.
 */
final class TypedSyntheticDeployment implements AutoCloseable {

    private static final long WAIT_SECONDS = 15;

    private final Vertx vertx;
    private final WebClient client;
    private final String deploymentId;
    private final int port;

    private TypedSyntheticDeployment(Vertx vertx, WebClient client, String deploymentId, int port) {
        this.vertx = vertx;
        this.client = client;
        this.deploymentId = deploymentId;
        this.port = port;
    }

    /**
     * Deploys a new verticle of a composition and opens a client to its server.
     *
     * @param verticle creates the composition's verticle
     * @return the running deployment
     * @throws Exception if the deployment fails, after every resource opened so far was closed
     */
    static TypedSyntheticDeployment deploy(Supplier<HttpVerticle> verticle) throws Exception {
        Vertx vertx = Vertx.vertx();
        WebClient client = null;
        try {
            client = WebClient.create(vertx, new WebClientOptions().setFollowRedirects(false));
            Supplier<Verticle> supplier = verticle::get;
            String id = await(vertx.deployVerticle(supplier, new DeploymentOptions()));
            int port = (Integer) vertx.sharedData().getLocalMap("vertique").get("http.port");
            return new TypedSyntheticDeployment(vertx, client, id, port);
        } catch (Throwable failure) {
            CleanupFailures cleanup = new CleanupFailures();
            WebClient opened = client;
            cleanup.attempt(() -> {
                if (opened != null) {
                    opened.close();
                }
            });
            cleanup.await(vertx::close, WAIT_SECONDS, TimeUnit.SECONDS);
            try {
                cleanup.rethrowIfAny();
            } catch (Exception secondary) {
                failure.addSuppressed(secondary);
            }
            throw failure;
        }
    }

    /**
     * Sends a GET request to the deployment's server.
     *
     * @param path    the request path
     * @param subject the bearer subject, or {@code null} to send no credential
     * @param roles   the caller's comma-separated roles, or {@code null} to send none
     * @param scopes  the caller's space-separated scopes, or {@code null} to send none
     * @return the response
     */
    HttpResponse<Buffer> get(String path, String subject, String roles, String scopes) {
        HttpRequest<Buffer> request = client.get(port, "127.0.0.1", path);
        if (subject != null) {
            request.putHeader("Authorization", "Bearer " + subject);
        }
        if (roles != null) {
            request.putHeader("X-Test-Roles", roles);
        }
        if (scopes != null) {
            request.putHeader("X-Test-Scopes", scopes);
        }
        return await(request.send());
    }

    @Override
    public void close() throws Exception {
        CleanupFailures cleanup = new CleanupFailures();
        cleanup.attempt(client::close);
        cleanup.await(() -> vertx.undeploy(deploymentId), WAIT_SECONDS, TimeUnit.SECONDS);
        cleanup.await(vertx::close, WAIT_SECONDS, TimeUnit.SECONDS);
        cleanup.rethrowIfAny();
    }

    private static <T> T await(Future<T> future) {
        try {
            return future.toCompletionStage().toCompletableFuture().get(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new AssertionError("request failed: " + e.getMessage(), e);
        }
    }
}
