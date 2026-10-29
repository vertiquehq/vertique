// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.engine;

import dev.vertique.core.lifecycle.LifecyclePhase;
import dev.vertique.deploy.VerticleDeployer;
import dev.vertique.deploy.VerticleDeployment;
import dev.vertique.workflow.config.WorkflowExecutorConfig;
import dev.vertique.workflow.engine.executor.WorkflowExecutor;
import dev.vertique.workflow.engine.executor.WorkflowExecutorVerticle;
import dev.vertique.workflow.engine.executor.WorkflowSegments;
import dev.vertique.workflow.engine.spi.CommandHead;
import dev.vertique.workflow.engine.spi.CommandWakeups;
import dev.vertique.workflow.engine.spi.WorkflowCommandRepository;
import dev.vertique.workflow.engine.spi.WorkflowTransactionRunner;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.registry.store.DefinitionKey;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.SqlClient;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Deploys the workflow executor of a Dagger graph that runs no deployer, and nothing else of the graph.
 *
 * <p>The harnesses of this module drive the rest of their graph by hand. They deploy only the {@code workflow-executor}
 * entry of the graph's deployments through a {@link VerticleDeployer}, never a whole lifecycle phase, because a phase
 * would also start the other verticles of the graph.
 */
public final class ExecutorDeployment {

    /** The name of the executor's deployment. */
    public static final String EXECUTOR = "workflow-executor";

    private final VerticleDeployer deployer;

    private ExecutorDeployment(VerticleDeployer deployer) {
        this.deployer = deployer;
    }

    /**
     * Deploys the executor entry of the graph's deployments.
     *
     * @param vertx       the Vert.x instance to deploy on
     * @param deployments the graph's verticle deployments
     * @return a future of the harness, which undeploys the executor
     */
    public static Future<ExecutorDeployment> deploy(Vertx vertx, Set<VerticleDeployment> deployments) {
        VerticleDeployment executor = deployments.stream()
                .filter(deployment -> EXECUTOR.equals(deployment.name()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("The graph contributes no " + EXECUTOR + " deployment"));
        VerticleDeployer deployer = new VerticleDeployer(vertx);
        return deployer.deploy(executor).map(id -> new ExecutorDeployment(deployer));
    }

    /**
     * Deploys an executor that peeks only the commands whose pinned definition the registry holds. A harness that runs
     * several graphs with different registries over one database uses it so that no graph's executor looks at, and so
     * defers, a command another graph runs.
     *
     * @param vertx    the Vert.x instance to deploy on
     * @param commands the graph's command repository
     * @param runner   the graph's transaction runner
     * @param segments the graph's side of a segment
     * @param registry the graph's registry, which decides what the executor peeks
     * @return a future of the harness, which undeploys the executor
     */
    @SuppressWarnings("unchecked")
    public static Future<ExecutorDeployment> deployHoldingOnly(
            Vertx vertx,
            WorkflowCommandRepository<SqlClient> commands,
            WorkflowTransactionRunner<SqlClient> runner,
            WorkflowSegments segments,
            WorkflowRegistry registry) {
        WorkflowCommandRepository<SqlClient> holding = (WorkflowCommandRepository<SqlClient>) Proxy.newProxyInstance(
                WorkflowCommandRepository.class.getClassLoader(),
                new Class<?>[] {WorkflowCommandRepository.class},
                (proxy, method, arguments) -> {
                    boolean peek = "peek".equals(method.getName());
                    Object[] forwarded = arguments;
                    int limit = 0;
                    if (peek) {
                        // read wide, so that the commands of another graph do not crowd out the graph's own
                        limit = (Integer) arguments[0];
                        forwarded = new Object[] {Math.max(limit, 256)};
                    }
                    Object result;
                    try {
                        result = method.invoke(commands, forwarded);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                    if (!peek) {
                        return result;
                    }
                    int wanted = limit;
                    return ((Future<List<CommandHead>>) result).map(heads -> heads.stream()
                            .filter(head -> head.instanceEnded()
                                    || registry.find(new DefinitionKey(
                                                    head.definitionScopeKey(),
                                                    head.definitionId(),
                                                    head.definitionVersion()))
                                            .isPresent())
                            .limit(wanted)
                            .toList());
                });
        WorkflowExecutorConfig config = new WorkflowExecutorConfig(1, 8, 100, 60);
        VerticleDeployment deployment = new VerticleDeployment(
                EXECUTOR,
                () -> new WorkflowExecutorVerticle(
                        config,
                        holding,
                        runner,
                        segments,
                        "holding-only-" + UUID.randomUUID(),
                        Set.of(),
                        CommandWakeups.none()),
                new DeploymentOptions().setInstances(1),
                LifecyclePhase.SERVICES,
                WorkflowExecutor.DEPLOYMENT_PRIORITY);
        VerticleDeployer deployer = new VerticleDeployer(vertx);
        return deployer.deploy(deployment).map(id -> new ExecutorDeployment(deployer));
    }

    /**
     * Undeploys the executor by name. Call it before the graph closes.
     *
     * @return a future that completes once the executor ended its in-flight segments
     */
    public Future<Void> undeploy() {
        return deployer.undeploy(EXECUTOR);
    }

    /** Dagger module for a test graph: an executor that polls every 100 ms instead of the production default. */
    @dagger.Module
    public abstract static class FastPolling {

        private FastPolling() {}

        @dagger.Provides
        @jakarta.inject.Singleton
        static dev.vertique.workflow.config.WorkflowExecutorConfig executorConfig() {
            return new dev.vertique.workflow.config.WorkflowExecutorConfig(1, 8, 100, 60);
        }
    }
}
