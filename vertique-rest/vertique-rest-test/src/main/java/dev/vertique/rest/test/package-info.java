// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Test-support fixture for assembling a production-faithful {@link
 * dev.vertique.rest.jaxrs.JaxRsRouterMount} from outside {@code dev.vertique.rest.jaxrs}.
 *
 * <p>This package ships five public types. {@code RestTestFixtureModule} is a Dagger {@code @Module}
 * that includes the real {@link dev.vertique.rest.jaxrs.RestModule} and {@code ConfigParsingModule},
 * and unions a consumer's additional test-only middlewares, request interceptors, response body
 * encoders, exception mappers, JSON mapper profiles, and file-content verifiers into the same
 * multibindings the framework uses in production. {@code RestTestNoSecurityModule} is the separate,
 * explicitly included module supplying the {@code null} {@code SecurityPolicyValidator} an unsecured
 * graph needs — separate precisely so a graph that includes {@code AuthModule} instead can claim that
 * same Dagger key and get the framework's real policy validation. {@code RestTestContributions} is
 * the immutable, additive-only record a consumer builds its contributions with and binds via
 * {@code @BindsInstance}. {@code RestTestMount} is the opaque handle the graph produces, carrying the
 * mount factory together with the graph's complete middleware set. {@code RestTestMounts} is a pure
 * Vert.x helper — no Dagger, no JUnit — that turns that handle into a mounted {@code Router} or a
 * running {@code HttpServer} for HTTP-level assertions.
 *
 * <p>A consumer never calls the 29-argument {@link dev.vertique.rest.jaxrs.JaxRsRouterMount.Factory}
 * constructor directly and never sorts encoder or decoder lists itself: Dagger builds the graph, so
 * ordering and serializer coherence hold by construction. Contributions are additive only — a test
 * overrides a framework default by contributing one that out-ranks it (see {@link
 * dev.vertique.rest.core.response.ResponseBodyEncoder}'s documented priority model), never by
 * replacing a production collaborator.
 *
 * <p>Each consuming Maven module declares its own package-private test {@code @Component} that
 * includes {@code RestTestFixtureModule}, exactly one supplier of the {@code SecurityPolicyValidator}
 * key ({@code RestTestNoSecurityModule} or {@code AuthModule}), and any strategy module the consumer
 * needs (for example the validation module that supplies a real request-validation strategy). This
 * package
 * depends only on {@code vertique-rest-jaxrs}, {@code vertique-rest-core}, {@code
 * vertique-config-core}, {@code dagger}, {@code vertx-core}, and {@code vertx-web} — it carries no
 * JUnit dependency and no test-framework coupling, so it stays usable from any test harness a
 * consumer chooses.
 */
package dev.vertique.rest.test;
