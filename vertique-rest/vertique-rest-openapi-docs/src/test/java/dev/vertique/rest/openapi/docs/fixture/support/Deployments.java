// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.support;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.Verticle;
import io.vertx.core.Vertx;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Bounded deployment helpers for integration tests that deploy a component's HTTP verticle. Every wait
 * is bounded by the caller's {@code bound}.
 */
public final class Deployments {

    private Deployments() {}

    /**
     * Removes the {@code http.port} entry of the {@code vertique} local map, deploys the supplier,
     * records the deployment id, and returns the port the deployment published in that map.
     *
     * @param vertx the Vert.x instance
     * @param supplier the verticle supplier, typically a component's {@code httpVerticle} method
     * @param options the deployment options
     * @param deploymentIds receives the id of the new deployment
     * @param bound the longest the deployment is awaited
     * @return the published HTTP port
     * @throws Exception when the deployment fails, times out, or publishes no port
     */
    public static int deployAndReadPort(
            Vertx vertx,
            Supplier<Verticle> supplier,
            DeploymentOptions options,
            List<String> deploymentIds,
            Duration bound)
            throws Exception {
        vertx.sharedData().getLocalMap("vertique").remove("http.port");
        deploymentIds.add(Futures.await(deploy(vertx, supplier, options), bound));
        Object port = vertx.sharedData().getLocalMap("vertique").get("http.port");
        assertNotNull(port, "the deployment published no http.port");
        return (Integer) port;
    }

    /**
     * Deploys a supplier that is expected to fail and returns the cause of the failure. A
     * synchronous throw during deployment counts as a failed deployment. When the deployment
     * succeeds it is undeployed and the test fails.
     *
     * @param vertx the Vert.x instance
     * @param supplier the verticle supplier
     * @param options the deployment options
     * @param bound the longest the deployment, and then a cleanup undeployment, is awaited
     * @return the cause of the failed deployment
     * @throws Exception when waiting is interrupted or the cleanup undeploy fails
     */
    public static Throwable failureOf(
            Vertx vertx, Supplier<Verticle> supplier, DeploymentOptions options, Duration bound) throws Exception {
        String deploymentId;
        try {
            deploymentId = deploy(vertx, supplier, options)
                    .toCompletionStage()
                    .toCompletableFuture()
                    .get(bound.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException failed) {
            return failed.getCause();
        } catch (TimeoutException incomplete) {
            throw new AssertionError("the deployment neither succeeded nor failed within " + bound);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        }
        Futures.await(vertx.undeploy(deploymentId), bound);
        throw new AssertionError("the deployment succeeded but was expected to fail");
    }

    /**
     * Undeploys each recorded deployment, waiting up to {@code bound} for each.
     *
     * @param vertx the Vert.x instance
     * @param deploymentIds the deployment ids to undeploy
     * @param bound the longest each undeployment is awaited
     * @throws Exception when an undeploy fails or times out
     */
    public static void undeployAll(Vertx vertx, List<String> deploymentIds, Duration bound) throws Exception {
        for (String deploymentId : deploymentIds) {
            Futures.await(vertx.undeploy(deploymentId), bound);
        }
    }

    /**
     * Deploys a supplier; a synchronous throw becomes a failed future.
     *
     * @param vertx the Vert.x instance
     * @param supplier the verticle supplier
     * @param options the deployment options
     * @return the deployment's id, or its failure
     */
    public static Future<String> deploy(Vertx vertx, Supplier<Verticle> supplier, DeploymentOptions options) {
        try {
            return vertx.deployVerticle(supplier, options);
        } catch (Throwable t) {
            return Future.failedFuture(t);
        }
    }
}
