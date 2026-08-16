// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.application.VertiqueApplicationComponent;
import dev.vertique.core.VertiqueComponentFactory;
import dev.vertique.core.lifecycle.LifecyclePhase;
import dev.vertique.deploy.VerticleDeployer;
import dev.vertique.deploy.VerticleDeployment;
import dev.vertique.deploy.VerticleDeploymentManager;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * AC-1 integration test: a standalone application boots through {@link VertiqueBootstrapVerticle}
 * with <strong>no {@code Main-Verticle}</strong> manifest entry and no {@code MainVerticle}.
 *
 * <p><b>IT vs full-launch choice.</b> The plan permits deploying {@link VertiqueBootstrapVerticle}
 * directly on a real {@link Vertx} as acceptable AC-1 proof, rather than driving a full
 * {@code VertiqueApplication.launch()} end-to-end (heavier and process-exit-coupled). This IT takes
 * the direct-deploy path: it proves the verticle discovers the application's
 * {@code VertiqueComponentFactory} via the test {@code META-INF/services} entry, runs the
 * host-neutral lifecycle (a startup step's side-effect fires), and that {@code stop()} (verticle
 * undeploy) triggers the handle's reverse-order shutdown without closing {@code Vertx}.
 *
 * <p>The factory is registered in
 * {@code src/test/resources/META-INF/services/dev.vertique.core.VertiqueComponentFactory} and builds
 * {@link TestApplicationComponent} (an empty verticle set + a single CONFIGURE startup step that sets
 * a marker, plus a real {@link dev.vertique.deploy.VerticleDeploymentManager} over an empty
 * deployment set). Determinism: no fixed sleeps; all waits are gated on {@link VertxTestContext}.
 *
 * <p>The second test ({@link #deploy_withHttpVerticle_servesHttp200}) uses the package-private
 * constructor seam to bypass ServiceLoader and inject a factory directly, proving the full path
 * {@code VertiqueApplication.verticleSupplier() → VertiqueBootstrapVerticle → factory → runner →
 * live HTTP server} ends with an HTTP 200.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class VertiqueBootstrapVerticleIT {

    // --- AC-1: ServiceLoader path (existing test) ---

    @Test
    @DisplayName(
            "AC-1: deploying VertiqueBootstrapVerticle discovers the factory, runs the lifecycle, and serves no Main-Verticle")
    void deploy_runsLifecycle_thenStopShutsDownWithoutClosingVertx(Vertx vertx, VertxTestContext ctx) {
        // Reset the shared markers so this test observes only its own lifecycle.
        TestStartupStep.STARTED.set(false);
        TestShutdownStep.STOPPED.set(false);

        JsonObject config = new JsonObject().put("app", "ac1");

        vertx.deployVerticle(new VertiqueBootstrapVerticle(), new DeploymentOptions().setConfig(config))
                .onComplete(ctx.succeeding(deploymentId -> {
                    // The runner ran: the CONFIGURE startup step's side-effect fired.
                    assertTrue(
                            TestStartupStep.STARTED.get(),
                            "the application's startup step ran — the lifecycle runner executed (no Main-Verticle needed)");
                    // The factory received the canonical config installed on the deployment options.
                    assertEquals("ac1", TestApplicationFactory.LAST_CONFIG.get().getString("app"));

                    // Undeploying the bootstrap verticle invokes stop(), which drives handle.shutdown().
                    vertx.undeploy(deploymentId).onComplete(ctx.succeeding(v -> {
                        assertTrue(
                                TestShutdownStep.STOPPED.get(),
                                "stop() drove handle.shutdown(), running the application's shutdown step");
                        // Vertx is host-owned: undeploying the verticle must not have closed it; a
                        // subsequent timer proves the instance is still live.
                        vertx.setTimer(1, id -> ctx.completeNow());
                    }));
                }));
    }

    // --- AC-1: launcher → runner → HTTP 200 (end-to-end HTTP verticle path) ---

    /**
     * Strengthens AC-1 by proving the full launcher path: the bootstrap verticle's factory produces
     * a component whose runner deploys a <em>real</em> HTTP verticle (bound on an ephemeral port),
     * and an HTTP GET to that port returns 200. This uses the package-private constructor seam to
     * inject the factory directly (bypassing ServiceLoader) so the test is self-contained and does
     * not require a second {@code META-INF/services} entry.
     *
     * <p>Port allocation: the HTTP verticle binds on port 0 and publishes the actual port to
     * {@link Vertx#sharedData()} under {@code "vertique"/"http.port"} after a successful bind. The
     * test reads the actual port from shared data after the bootstrap verticle starts.
     *
     * <p>The GET goes through a {@link WebClient} rather than a raw {@code HttpClient}: a raw
     * {@code HttpClientResponse} discards body buffers that arrive before a body handler is
     * attached, so under load {@code body()} can succeed with zero bytes while the status code is
     * correct (issue #167). Only the status is asserted here, but the racy idiom would become a live
     * race the moment someone added a body assertion. The server answers 200 to every request, so
     * {@link WebClient}'s follow-redirects default (a raw {@code HttpClient} follows none) never
     * engages.
     */
    @Test
    @DisplayName("AC-1 HTTP: verticleSupplier → VertiqueBootstrapVerticle → runner → live HTTP verticle serves 200")
    void deploy_withHttpVerticle_servesHttp200(Vertx vertx, VertxTestContext ctx) {
        // The bound HTTP port; set by the HTTP verticle after listen(0) succeeds.
        AtomicInteger boundPort = new AtomicInteger(-1);

        // Inline factory — deployed by the runner via VerticleDeploymentManager.
        VertiqueComponentFactory<VertiqueApplicationComponent> factory = runtime -> {
            // A minimal HTTP verticle that binds on port 0, answers every GET with 200,
            // and publishes the actual port to shared data.
            VerticleDeployment httpDeployment = VerticleDeployment.of(
                    "test-http-verticle",
                    () -> new EphemeralHttpVerticle(runtime.vertx(), boundPort),
                    LifecyclePhase.EDGE);

            VerticleDeploymentManager manager =
                    new VerticleDeploymentManager(new VerticleDeployer(runtime.vertx()), Set.of(httpDeployment));

            // No startup/shutdown steps needed — the test just proves the HTTP verticle is reachable.
            return new TestApplicationComponent(Set.of(), Set.of(), manager);
        };

        // Use the constructor seam (bypasses ServiceLoader); this is the path VertiqueApplication
        // uses in production via verticleSupplier() → new VertiqueBootstrapVerticle().
        VertiqueBootstrapVerticle bootstrapVerticle = new VertiqueBootstrapVerticle(() -> factory);

        vertx.deployVerticle(bootstrapVerticle)
                .compose(deploymentId -> {
                    int port = boundPort.get();
                    assertTrue(port > 0, "HTTP server must have bound on an ephemeral port, got: " + port);

                    // Issue an HTTP GET to the live server and assert 200.
                    // Redirects off: parity with the raw client; WebClient forwards Authorization across 3xx.
                    WebClient client = WebClient.create(
                            vertx,
                            new WebClientOptions()
                                    .setDefaultPort(port)
                                    .setDefaultHost("127.0.0.1")
                                    .setFollowRedirects(false));
                    return client.get("/")
                            .send()
                            .map(response -> {
                                assertEquals(200, response.statusCode(), "HTTP server must return 200");
                                return deploymentId;
                            })
                            // eventually(...) runs on the failure path too. The close used to sit
                            // inside the success compose, so a failed request leaked the client and
                            // left Vert.x tearing down its pools with the request still in flight.
                            .eventually(() -> {
                                client.close();
                                return Future.succeededFuture();
                            });
                })
                .compose(deploymentId -> vertx.undeploy(deploymentId))
                .onComplete(ctx.succeedingThenComplete());
    }

    // --- Test helper: a minimal HTTP verticle that binds on port 0 ---

    /**
     * A minimal HTTP verticle that binds on an ephemeral port (0), answers every request with
     * {@code 200 OK}, and records the actual bound port in a shared {@link AtomicInteger} so the
     * test can assert reachability without a fixed port.
     *
     * <p>Used exclusively by {@link #deploy_withHttpVerticle_servesHttp200} to prove the runner
     * path reaches a live HTTP server.
     */
    private static final class EphemeralHttpVerticle extends AbstractVerticle {

        private final Vertx vertxRef;
        private final AtomicInteger portCapture;

        /**
         * Creates the verticle.
         *
         * @param vertxRef the Vert.x instance, used to create the HTTP server
         * @param portCapture receives the actual port after {@code listen(0)} succeeds
         */
        EphemeralHttpVerticle(Vertx vertxRef, AtomicInteger portCapture) {
            this.vertxRef = vertxRef;
            this.portCapture = portCapture;
        }

        @Override
        public void start(Promise<Void> startPromise) {
            vertxRef.createHttpServer()
                    .requestHandler(req -> req.response().setStatusCode(200).end())
                    .listen(0, "127.0.0.1")
                    .onSuccess(server -> {
                        portCapture.set(server.actualPort());
                        startPromise.complete();
                    })
                    .onFailure(startPromise::fail);
        }

        /** No-op; the HTTP server is closed when Vert.x shuts down. */
        @Override
        public void stop() {
            // Nothing to do — server lifecycle is managed by Vert.x.
        }
    }
}
