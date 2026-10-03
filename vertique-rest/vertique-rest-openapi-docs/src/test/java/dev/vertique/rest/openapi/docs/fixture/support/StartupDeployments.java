// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.support;

import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.Verticle;
import io.vertx.core.Vertx;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Deploys a verticle supplier and reports its outcome: the failure, or the port the deployment
 * published under {@value #PORT_KEY} in the {@value #LOCAL_MAP} local map. A supplier that throws
 * while it provisions its component counts as a failed deployment. Every wait is bounded by
 * {@link #BOUND}; a deployment or undeployment that does not complete within it fails the test.
 */
public final class StartupDeployments {

    /** The local map in which a deployment publishes its port. */
    public static final String LOCAL_MAP = "vertique";

    /** The key under which a deployment publishes its port. */
    public static final String PORT_KEY = "http.port";

    /** The longest one deployment or undeployment is awaited. */
    public static final Duration BOUND = Duration.ofSeconds(10);

    private StartupDeployments() {}

    /**
     * The outcome of one deployment.
     *
     * @param failure      the deployment's failure, or {@code null} when it deployed
     * @param port         the port found under {@value #PORT_KEY} once the deployment completed,
     *                     successfully or not, or {@code null} when none was published
     * @param deploymentId the deployment id, or {@code null} when the deployment failed
     */
    public record Outcome(Throwable failure, Integer port, String deploymentId) {

        /**
         * Returns whether the deployment succeeded.
         *
         * @return {@code true} when there is no failure
         */
        public boolean deployed() {
            return failure == null;
        }
    }

    /**
     * Deploys a supplier with default deployment options.
     *
     * @param vertx     the Vert.x instance
     * @param verticles the verticle supplier, typically a component's {@code httpVerticle}
     * @return the outcome
     * @throws Exception when the deployment does not complete within {@link #BOUND}
     */
    public static Outcome deploy(Vertx vertx, Supplier<Verticle> verticles) throws Exception {
        return deploy(vertx, verticles, new DeploymentOptions());
    }

    /**
     * Removes {@value #PORT_KEY} from the {@value #LOCAL_MAP} local map, deploys a supplier, and
     * waits for the outcome. A synchronous throw from the supplier or from the deploy call becomes
     * the failure. The port is read from the local map on both success and failure.
     *
     * @param vertx     the Vert.x instance
     * @param verticles the verticle supplier, typically a component's {@code httpVerticle}
     * @param options   the deployment options
     * @return the outcome
     * @throws Exception when the deployment does not complete within {@link #BOUND}
     */
    public static Outcome deploy(Vertx vertx, Supplier<Verticle> verticles, DeploymentOptions options)
            throws Exception {
        vertx.sharedData().getLocalMap(LOCAL_MAP).remove(PORT_KEY);
        Future<String> deployment;
        try {
            deployment = vertx.deployVerticle(verticles, options);
        } catch (Throwable t) {
            deployment = Future.failedFuture(t);
        }
        Throwable failure = null;
        String deploymentId = null;
        try {
            deploymentId =
                    deployment.toCompletionStage().toCompletableFuture().get(BOUND.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException failed) {
            failure = failed.getCause();
        } catch (TimeoutException incomplete) {
            throw new AssertionError("the deployment did not complete within " + BOUND, incomplete);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        }
        Object port = vertx.sharedData().getLocalMap(LOCAL_MAP).get(PORT_KEY);
        return new Outcome(failure, (Integer) port, deploymentId);
    }

    /**
     * Undeploys a successful deployment, if any, then removes {@value #PORT_KEY} from the
     * {@value #LOCAL_MAP} local map.
     *
     * @param vertx   the Vert.x instance
     * @param outcome the deployment's outcome; nothing is undeployed when it has no deployment id
     * @throws Exception when the undeployment fails or does not complete within {@link #BOUND}
     */
    public static void undeploy(Vertx vertx, Outcome outcome) throws Exception {
        try {
            if (outcome != null && outcome.deploymentId() != null) {
                Futures.await(vertx.undeploy(outcome.deploymentId()), BOUND);
            }
        } finally {
            vertx.sharedData().getLocalMap(LOCAL_MAP).remove(PORT_KEY);
        }
    }

    /**
     * Undeploys a successful deployment, if any, then clears the {@value #LOCAL_MAP} local map, even
     * when the undeployment fails.
     *
     * @param vertx   the Vert.x instance
     * @param outcome the deployment's outcome; nothing is undeployed when it has no deployment id
     * @throws Exception when the undeployment fails or does not complete within {@link #BOUND}
     */
    public static void undeployAndClear(Vertx vertx, Outcome outcome) throws Exception {
        try {
            undeploy(vertx, outcome);
        } finally {
            vertx.sharedData().getLocalMap(LOCAL_MAP).clear();
        }
    }
}
