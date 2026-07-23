// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Dagger-based verticle deployment with phase orchestration, multi-instance, and priority support.
 *
 * <p>Key types:
 * <ul>
 *   <li>{@link dev.vertique.deploy.VerticleDeployment} — deployment descriptor (name, supplier,
 *       options, phase, priority)</li>
 *   <li>{@link dev.vertique.deploy.VerticleDeployer} — low-level deploy/undeploy/track engine</li>
 *   <li>{@link dev.vertique.deploy.VerticleDeploymentManager} — phase orchestrator for
 *       multibound verticle sets (deployAll, deployPhase)</li>
 *   <li>{@link dev.vertique.deploy.DeployerModule} — Dagger module declaring the multibinding
 *       set</li>
 * </ul>
 */
package dev.vertique.deploy;
