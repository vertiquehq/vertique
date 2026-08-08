// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Root package for the example-services-codegen application.
 *
 * <p>Demonstrates end-to-end use of {@code vertique-codegen-services}: two commerce service
 * contracts are registered at compile time via generated {@code ServiceContractContributor}
 * implementations, and their singleton typed clients are provided by the same generated Dagger
 * module. This eliminates both manual {@code @IntoSet} wiring and manual client providers.
 *
 * <p>The application is bootstrapped via the {@code @VertiqueApp}-annotated
 * {@link dev.vertique.examples.services.codegen.AppComponent} — the annotation processor generates
 * {@code AppComponentVertiqueComponentFactory} and the host-neutral lifecycle runner
 * ({@code VertiqueApplicationBootstrap}) drives startup and shutdown without a hand-written
 * {@code MainVerticle}. The runner reproduces the old startup choreography: Jackson configuration
 * runs as a {@code CONFIGURE}-phase step; management verticle deploys in {@code INFRA}; service
 * verticles deploy via the {@code SERVICES}-phase step contributed by {@code DispatchModule};
 * the HTTP verticle deploys in {@code EDGE}.
 *
 * <p>Key classes:
 * <ul>
 *   <li>{@link dev.vertique.examples.services.codegen.AppComponent} — Dagger root component</li>
 *   <li>{@link dev.vertique.examples.services.codegen.AppModule} — security stubs and verticle registrations</li>
 *   <li>{@code GeneratedServicesModule} — generated service registrations and typed clients</li>
 * </ul>
 */
package dev.vertique.examples.services.codegen;
