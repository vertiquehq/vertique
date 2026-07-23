// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Host-neutral application-lifecycle vocabulary and step contracts.
 *
 * <p>This package defines the single public lifecycle phase enum, {@link
 * dev.vertique.core.lifecycle.LifecyclePhase}, and the contributor SPIs that hang off it. The phase
 * vocabulary spans the whole application lifecycle — pre-deploy preparation, verticle deployment, and
 * post-start work — so a host (standalone launcher, Spring, or Quarkus bridge) can describe and order
 * every startup/shutdown unit against one shared set of phases.
 *
 * <p>Key types:
 * <ul>
 *   <li>{@link dev.vertique.core.lifecycle.LifecyclePhase} — the eight ordered lifecycle phases; the
 *       {@code BOOTSTRAP/INFRA/SERVICES/EDGE} subset are the only phases that may carry a verticle
 *       deployment ({@link dev.vertique.core.lifecycle.LifecyclePhase#isVerticlePhase()}).</li>
 *   <li>{@link dev.vertique.core.lifecycle.LifecycleOrdered} — the shared ordering contract
 *       (phase &rarr; priority &rarr; orderKey) for any lifecycle participant.</li>
 *   <li>{@link dev.vertique.core.lifecycle.ApplicationStartupStep} — a non-verticle startup unit run
 *       in phase order during application start.</li>
 *   <li>{@link dev.vertique.core.lifecycle.ApplicationShutdownStep} — a non-verticle shutdown unit
 *       run during application stop.</li>
 * </ul>
 *
 * <p>The step SPIs are Dagger {@code @IntoSet}-contributed and ordered with {@link
 * dev.vertique.core.lifecycle.LifecycleOrdered#comparator()}.
 */
package dev.vertique.core.lifecycle;
