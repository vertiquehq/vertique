// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Vertique bootstrap kernel: the pre-Vertx seam that customizes the {@code Vertx.builder()} call
 * before the {@link io.vertx.core.Vertx} instance is created.
 *
 * <p>This package holds the {@code VertxBuilderContributor} SPI discovered via
 * {@link java.util.ServiceLoader} from
 * {@code META-INF/services/dev.vertique.bootstrap.VertxBuilderContributor} entries on the
 * classpath, the {@code BootstrapContext} view passed to each contributor, and the
 * {@code ContributorRunner} that discovers, orders, and drives the contributor chain (and runs the
 * shutdown hooks). The framework-owned application entrypoint that consumes this kernel
 * ({@code VertiqueApplication}) lives in the {@code vertique-launcher} module.
 *
 * <p>Module-boundary rule: this module is the pre-DI bootstrap seam — it runs before any Dagger
 * graph exists, and it must therefore <em>never</em> depend on Dagger-graph modules
 * ({@code dagger}, {@code vertique-rest-*}, service modules, or any module that assumes an injected
 * {@code Vertx} is already available). ServiceLoader discovery here is the deliberate exception in a
 * Dagger-first codebase: contributors are resolved via the standard Java SPI mechanism rather than
 * Dagger multibinding precisely because the Dagger graph is not yet initialised at this point. See
 * ADR-0095 for the recorded rationale.
 */
package dev.vertique.bootstrap;
