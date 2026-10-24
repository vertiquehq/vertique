// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.order;

import dev.vertique.deploy.VerticleDeployer;
import dev.vertique.deploy.VerticleDeployment;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import java.util.Set;

/**
 * Deploys the workflow executor of a Dagger graph that runs no deployer, and nothing else of the graph.
 *
 * <p>The harnesses of this module drive the rest of their graph by hand. They deploy only the {@code workflow-executor}
 * entry of the graph's deployments through a {@link VerticleDeployer}, never a whole lifecycle phase, because a phase
 * would also start the other verticles of the graph.
 */
public final class WorkflowExecutorHarness {

    /** The name of the executor's deployment. */
    public static final String EXECUTOR = "workflow-executor";

    private final VerticleDeployer deployer;

    private WorkflowExecutorHarness(VerticleDeployer deployer) {
        this.deployer = deployer;
    }

    /**
     * Deploys the executor entry of the graph's deployments.
     *
     * @param vertx       the Vert.x instance to deploy on
     * @param deployments the graph's verticle deployments
     * @return a future of the harness, which undeploys the executor
     */
    public static Future<WorkflowExecutorHarness> deploy(Vertx vertx, Set<VerticleDeployment> deployments) {
        VerticleDeployment executor = deployments.stream()
                .filter(deployment -> EXECUTOR.equals(deployment.name()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("The graph contributes no " + EXECUTOR + " deployment"));
        VerticleDeployer deployer = new VerticleDeployer(vertx);
        return deployer.deploy(executor).map(id -> new WorkflowExecutorHarness(deployer));
    }

    /**
     * Undeploys the executor by name. Call it before the graph closes.
     *
     * @return a future that completes once the executor ended its in-flight segments
     */
    public Future<Void> undeploy() {
        return deployer.undeploy(EXECUTOR);
    }
}
