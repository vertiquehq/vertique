// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Example application demonstrating custom response serialization, SHA-256 digest headers,
 * and categorized error format.
 *
 * <p>The application is bootstrapped via the {@code @VertiqueApp}-annotated
 * {@link dev.vertique.examples.customresponse.AppComponent} — the annotation processor generates
 * {@code AppComponentVertiqueComponentFactory} and the host-neutral lifecycle runner
 * ({@code VertiqueApplicationBootstrap}) drives startup and shutdown without a hand-written
 * {@code MainVerticle}.
 *
 * <p>Key classes:
 * <ul>
 *   <li>{@link dev.vertique.examples.customresponse.AppComponent} — root Dagger
 *       {@code @Component} that wires together {@code VertxModule}, {@code RestModule},
 *       {@code JwtAuthModule}, {@code DeployerModule}, {@code CoreLifecycleStepsModule},
 *       {@code AppModule}, and {@code ResourceModule}</li>
 *   <li>{@link dev.vertique.examples.customresponse.DigestFilter} — validates incoming
 *       {@code Digest} request headers against the raw request body bytes and computes SHA-256
 *       {@code Digest} headers on every response</li>
 *   <li>{@link dev.vertique.examples.customresponse.CategorizedExceptionMapper} — replaces
 *       the default ProblemDetail error format with a categorized error envelope</li>
 * </ul>
 *
 * <p>This module serves as a reference implementation for framework extension points.
 */
package dev.vertique.examples.customresponse;
