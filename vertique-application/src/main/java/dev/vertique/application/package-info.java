// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Host-neutral application lifecycle runner.
 *
 * <p>This module owns the framework's startup/shutdown choreography, decoupled from any host
 * (standalone launcher, Spring, Quarkus). It depends only on {@code vertique-core} (the neutral
 * {@link dev.vertique.core.VertiqueRuntime} / {@link dev.vertique.core.VertiqueComponentFactory}
 * seam and the {@link dev.vertique.core.lifecycle lifecycle} step contracts) and {@code
 * vertique-deploy} (the {@link dev.vertique.deploy.VerticleDeploymentManager} verticle-phase
 * helper). It deliberately does <em>not</em> depend on {@code vertique-services}, {@code
 * vertique-workflow}, or {@code vertique-launcher}.
 *
 * <p>Key types:
 * <ul>
 *   <li>{@link dev.vertique.application.VertiqueApplicationComponent} — the passive contract an
 *       application's Dagger {@code @Component} extends; exposes the startup/shutdown step sets and
 *       the verticle deployment manager the runner drives</li>
 *   <li>{@link dev.vertique.application.VertiqueApplicationBootstrap} — the lifecycle-runner entry
 *       point: builds the component via a factory and drives the phases deterministically</li>
 *   <li>{@link dev.vertique.application.VertiqueApplicationHandle} — the started-application handle
 *       exposing idempotent {@code shutdown()} plus access to the component and runtime</li>
 *   <li>{@link dev.vertique.application.VertiqueApp} — the source-retained marker placed on an
 *       application's Dagger {@code @Component}; the {@code vertique-codegen-application} annotation
 *       processor generates the {@link dev.vertique.core.VertiqueComponentFactory} implementation
 *       and its {@code META-INF/services} registration from it</li>
 * </ul>
 */
package dev.vertique.application;
