// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Vertique launcher: the framework-owned application entrypoint extending Vert.x's
 * {@code VertxApplication}, consuming the {@code VertxBuilderContributor} bootstrap kernel from
 * {@code vertique-bootstrap} (package {@code dev.vertique.bootstrap}) before the
 * {@link io.vertx.core.Vertx} instance is built.
 *
 * <p>The {@code VertiqueApplication} class (in this package) is the single main-class entry point
 * for all Vertique-based applications. It delegates Vert.x lifecycle management to the parent
 * framework while allowing application-level customization of the {@code Vertx.builder()} call via
 * the {@code VertxBuilderContributor} SPI (defined in {@code vertique-bootstrap}). Contributors are
 * discovered through {@link java.util.ServiceLoader} (via
 * {@code dev.vertique.bootstrap.ContributorRunner}) and applied in order before the {@code Vertx}
 * instance is created. Shutdown hook propagation (registering a JVM shutdown hook that gracefully
 * stops the Vert.x instance) is also handled here.
 *
 * <p>Module-boundary rule: this module is the pre-DI bootstrap seam — it initialises the
 * {@code Vertx} instance before any Dagger graph exists, and it must therefore <em>never</em>
 * depend on Dagger-graph modules ({@code dagger}, {@code vertique-rest-*}, service modules, or
 * any module that assumes an injected {@code Vertx} is already available). ServiceLoader discovery
 * here is the deliberate exception in a Dagger-first codebase: contributors are resolved via the
 * standard Java SPI mechanism rather than Dagger multibinding precisely because the Dagger graph
 * is not yet initialised at this point. See ADR-0095 for the recorded rationale.
 */
package dev.vertique.launcher;
