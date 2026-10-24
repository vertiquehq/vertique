// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import io.vertx.core.Future;

/**
 * Starts and stops the executor of an engine: the verticle that claims recorded commands and runs them.
 *
 * <p>A Dagger-built application deploys the executor with the rest of the {@code SERVICES} phase and undeploys it at
 * shutdown, so it has no use for this control. An engine assembled through {@link WorkflowEngineFactory} deploys
 * nothing by itself: its owner starts the executor through this control and stops it before the engine's pool and
 * Vert.x instance close.
 */
public interface WorkflowExecutorControl {

    /**
     * Deploys the executor verticle with its configured number of instances.
     *
     * @return a future that completes once the executor is deployed; it fails when the executor is already started or
     *     the engine was assembled without a Vert.x instance
     */
    Future<Void> start();

    /**
     * Ends claiming and undeploys the executor. The future completes when every segment that was in flight has
     * committed or been rolled back by its own deadline; that is normally within the lease, and a statement stuck in
     * the database extends it. A segment rolled back this way leaves its claim to lapse, and another executor takes
     * the command once the lease has expired.
     *
     * @return a future that completes once the executor is undeployed; it completes at once when the executor is not
     *     started
     */
    Future<Void> stop();
}
