// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Example application demonstrating event bus service dispatch with contract-first interfaces.
 *
 * <p>There is no hand-written {@code MainVerticle}: the entry point is
 * {@link dev.vertique.examples.services.AppComponent}, the root Dagger {@code @Component} annotated
 * {@link dev.vertique.application.VertiqueApp} and extending
 * {@link dev.vertique.application.VertiqueApplicationComponent}. The
 * {@code vertique-codegen-application} annotation processor generates
 * {@code AppComponentVertiqueComponentFactory} (and its {@code META-INF/services} registration) for
 * that component; at runtime the launcher's bootstrap verticle discovers that factory and the
 * host-neutral lifecycle runner ({@code VertiqueApplicationBootstrap}) builds the component from the
 * pre-resolved {@code config()} tree and drives startup per lifecycle phase. The runner reproduces
 * the legacy deploy choreography: Jackson configuration via {@code CoreLifecycleStepsModule}
 * ({@code CONFIGURE}), the management verticle ({@code INFRA}), then the service verticles via the
 * {@code SERVICES}-phase {@code ServiceDeploymentStartupStep} contributed by {@code DispatchModule}
 * (running {@code serviceDeploymentManager().deployAll()} before any {@code SERVICES}-phase
 * verticle), then the {@link dev.vertique.rest.core.router.HttpVerticle} ({@code EDGE}) —
 * preserving the {@code INFRA} &rarr; service-dispatch-deploy &rarr; {@code EDGE} ordering.
 *
 * <p>{@link dev.vertique.examples.services.AppComponent} wires together {@code VertxModule},
 * {@code RestModule}, {@code DispatchModule}, {@code AppModule}, {@code ServiceModule}, and the
 * generated resource/service modules. This module serves as a reference implementation for building
 * Vert.x REST APIs backed by event bus services with resilience policies.
 */
package dev.vertique.examples.services;
