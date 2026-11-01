// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Root package of the workflow load node.
 *
 * <p>The module runs the real workflow engine, executor, outbox relay and intake sampler as one
 * launcher-started process, so a load run can start several such processes against one PostgreSQL
 * database. The process has no REST layer: a small loopback ingress starts workflow instances through
 * the Dagger-wired workflow client, and a migrate-once entry point prepares the schema before any node
 * starts.
 *
 * <p>The numbers this module produces are measurements of one setup on one machine. They are not a
 * benchmark of the framework and not a statement of product capacity.
 *
 * <p>Key classes:
 * <ul>
 *   <li>{@link dev.vertique.examples.workflow.load.AppComponent} — Dagger root component of a node</li>
 *   <li>{@link dev.vertique.examples.workflow.load.workflow.LoadDefinition} — the one workload
 *       definition</li>
 *   <li>{@link dev.vertique.examples.workflow.load.node.LoadServiceHandler} — the dispatch handler
 *       every node registers</li>
 *   <li>{@link dev.vertique.examples.workflow.load.node.IngressVerticle} — the loopback ingress</li>
 *   <li>{@link dev.vertique.examples.workflow.load.admin.LoadAdmin} — the migrate-once and
 *       trigger-state entry point</li>
 * </ul>
 */
package dev.vertique.examples.workflow.load;
