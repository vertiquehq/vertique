// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Example application demonstrating inbound REST locale negotiation end-to-end.
 *
 * <p>The application is bootstrapped via the {@code @VertiqueApp}-annotated
 * {@link dev.vertique.examples.localization.AppComponent} — the annotation processor generates
 * {@code AppComponentVertiqueComponentFactory} and the host-neutral lifecycle runner
 * ({@code VertiqueApplicationBootstrap}) drives startup and shutdown without a hand-written
 * {@code MainVerticle}. The runner reproduces the old startup choreography through lifecycle
 * phases: Jackson configuration runs as a {@code CONFIGURE}-phase step; management verticle
 * deploys in {@code INFRA}; service verticles deploy via the {@code SERVICES}-phase step
 * contributed by {@code DispatchModule}; the HTTP verticle deploys in {@code EDGE}.
 *
 * <p>Key classes:
 * <ul>
 *   <li>{@link dev.vertique.examples.localization.AppComponent} — root Dagger
 *       {@code @Component} that wires together {@code VertxModule}, {@code RestModule},
 *       {@code RestLocalizationModule}, {@code DispatchModule}, {@code CoreLifecycleStepsModule},
 *       {@code AppModule}, and {@code ServiceModule}</li>
 *   <li>{@link dev.vertique.examples.localization.QueryParamLocaleSource} — custom
 *       {@link dev.vertique.rest.localization.LocaleSource} contributed at priority {@code 0}
 *       (before the built-in {@code Accept-Language} source at priority {@code 1000}),
 *       demonstrating that a pre-authentication source can override the browser-supplied header</li>
 * </ul>
 */
package dev.vertique.examples.localization;
