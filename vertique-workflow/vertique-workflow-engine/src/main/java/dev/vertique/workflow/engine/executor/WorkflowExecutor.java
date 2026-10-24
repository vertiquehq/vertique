// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.executor;

import dev.vertique.core.lifecycle.LifecyclePhase;
import dev.vertique.deploy.VerticleDeployment;
import dev.vertique.workflow.config.WorkflowExecutorConfig;
import dev.vertique.workflow.engine.WorkflowExecutorControl;
import dev.vertique.workflow.engine.spi.WorkflowCommandRepository;
import dev.vertique.workflow.engine.spi.WorkflowTransactionRunner;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.SqlClient;
import jakarta.annotation.Nullable;
import java.net.InetAddress;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * INTERNAL: describes the {@code workflow-executor} deployment and deploys it for engines assembled outside Dagger.
 *
 * <p>A Dagger-built application takes {@link #deployment()} into its {@code SERVICES} phase and never calls
 * {@link #start()}. A factory-built engine's owner calls {@link #start()}, which deploys the same supplier and options
 * with {@code vertx.deployVerticle}, and {@link #stop()}, which undeploys that deployment.
 */
public final class WorkflowExecutor implements WorkflowExecutorControl {

    /** The name of the deployment. */
    public static final String DEPLOYMENT_NAME = "workflow-executor";

    /** The priority of the deployment within the {@code SERVICES} phase. */
    public static final int DEPLOYMENT_PRIORITY = 100;

    private final @Nullable Vertx vertx;
    private final WorkflowExecutorConfig config;
    private final WorkflowCommandRepository<SqlClient> commands;
    private final WorkflowTransactionRunner<SqlClient> runner;
    private final WorkflowSegments segments;
    private final String nodeId;

    private String deploymentId;
    private boolean starting;

    /**
     * Creates the executor of one engine.
     *
     * @param vertx    the Vert.x instance the executor is deployed on; {@code null} when the engine was assembled
     *                 without one, in which case {@link #start()} fails
     * @param config   the executor configuration
     * @param commands the command repository
     * @param runner   the transaction runner segments run through
     * @param segments the engine's side of a segment
     * @param nodeId   the node id the instances' ids are derived from; see {@link #newNodeId()}
     */
    public WorkflowExecutor(
            @Nullable Vertx vertx,
            WorkflowExecutorConfig config,
            WorkflowCommandRepository<SqlClient> commands,
            WorkflowTransactionRunner<SqlClient> runner,
            WorkflowSegments segments,
            String nodeId) {
        this.vertx = vertx;
        this.config = Objects.requireNonNull(config, "config");
        this.commands = Objects.requireNonNull(commands, "commands");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.segments = Objects.requireNonNull(segments, "segments");
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
    }

    /**
     * Generates a node id from the host name and a short random suffix, falling back to {@code unknown} when the host
     * name cannot be resolved.
     *
     * @return the node id
     */
    public static String newNodeId() {
        String hostname;
        try {
            hostname = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            hostname = "unknown";
        }
        return hostname + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * The deployment: {@code workflow-executor} in the {@code SERVICES} phase at priority 100 with the configured
     * number of instances on the event loop. Each call returns a deployment whose supplier numbers its instances from
     * zero, so the instances' ids are the node id plus {@code -0}, {@code -1} and so on.
     *
     * @return the deployment
     */
    public VerticleDeployment deployment() {
        AtomicInteger instanceCounter = new AtomicInteger();
        return new VerticleDeployment(
                DEPLOYMENT_NAME,
                () -> new WorkflowExecutorVerticle(
                        config, commands, runner, segments, nodeId + "-" + instanceCounter.getAndIncrement()),
                new DeploymentOptions().setInstances(config.instances()),
                LifecyclePhase.SERVICES,
                DEPLOYMENT_PRIORITY);
    }

    @Override
    public synchronized Future<Void> start() {
        if (vertx == null) {
            return Future.failedFuture(new IllegalStateException(
                    "The engine was assembled without a Vert.x instance, so its executor cannot be deployed"));
        }
        if (starting || deploymentId != null) {
            return Future.failedFuture(new IllegalStateException("The workflow executor is already started"));
        }
        starting = true;
        VerticleDeployment deployment = deployment();
        return vertx.deployVerticle(deployment.supplier(), deployment.options()).transform(result -> {
            synchronized (this) {
                starting = false;
                if (result.succeeded()) {
                    deploymentId = result.result();
                    return Future.<Void>succeededFuture();
                }
                return Future.<Void>failedFuture(result.cause());
            }
        });
    }

    @Override
    public Future<Void> stop() {
        String id;
        synchronized (this) {
            id = deploymentId;
            deploymentId = null;
        }
        if (id == null || vertx == null) {
            return Future.succeededFuture();
        }
        return vertx.undeploy(id);
    }
}
