// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Example application demonstrating Server-Sent Events with a simulated job progress feed. There is
 * no hand-written {@code MainVerticle}: the entry point is
 * {@link dev.vertique.examples.sse.AppComponent}, the root Dagger {@code @Component} annotated
 * {@link dev.vertique.application.VertiqueApp} and extending
 * {@link dev.vertique.application.VertiqueApplicationComponent}. The
 * {@code vertique-codegen-application} annotation processor generates
 * {@code AppComponentVertiqueComponentFactory} (and its {@code META-INF/services} registration) for
 * that component; at runtime the launcher's bootstrap verticle discovers that factory and the
 * host-neutral lifecycle runner ({@code VertiqueApplicationBootstrap}) builds the component from the
 * pre-resolved {@code config()} tree, runs the framework {@code CONFIGURE}/{@code VALIDATE} steps
 * (Jackson configuration via {@code CoreLifecycleStepsModule}), and deploys the verticles per
 * lifecycle phase ({@link dev.vertique.management.ManagementVerticle} in {@code INFRA}, then
 * {@link dev.vertique.rest.core.router.HttpVerticle} in {@code EDGE}).
 *
 * <p>{@code AppComponent} wires together {@code VertxModule}, {@code AppModule},
 * {@code RestModule}, and {@code JobModule}. {@code AppModule} binds application-specific
 * configuration values (HTTP port) and declares the verticle deployments. {@code JobModule} wires
 * the {@code JobService} and contributes the {@code JobResource} JAX-RS endpoint. This module
 * serves as a reference implementation for SSE streaming with a simulated long-running job.
 */
package dev.vertique.examples.sse;
