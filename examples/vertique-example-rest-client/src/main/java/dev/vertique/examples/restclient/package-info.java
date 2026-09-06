// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Example application demonstrating the declarative REST client module. There is no hand-written
 * {@code MainVerticle}: the entry point is {@link dev.vertique.examples.restclient.AppComponent},
 * the root Dagger {@code @Component} annotated {@link dev.vertique.application.VertiqueApp} and
 * extending {@link dev.vertique.application.VertiqueApplicationComponent}. The
 * {@code vertique-codegen-application} annotation processor generates
 * {@code AppComponentVertiqueComponentFactory} (and its {@code META-INF/services} registration) for
 * that component; at runtime the launcher's bootstrap verticle discovers that factory and the
 * host-neutral lifecycle runner ({@code VertiqueApplicationBootstrap}) builds the component from the
 * pre-resolved {@code config()} tree and runs the framework {@code CONFIGURE}/{@code VALIDATE} steps
 * (the process JSON codec install and the compose-validator harness).
 *
 * <p>This is a pure REST <em>client</em> application — it deploys no verticles. {@code AppComponent}
 * wires {@code VertxModule}, {@code RestClientModule}, {@code DeployerModule},
 * {@code CoreLifecycleStepsModule}, and the auto-generated {@code GeneratedRestClientsModule}. The
 * {@link dev.vertique.examples.restclient.client.UserClient} proxy's base URL is resolved from
 * {@code restClient.userService.baseUrl} in the application config.
 *
 * <p>The mock user service the client talks to ({@link
 * dev.vertique.examples.restclient.MockServerVerticle}, configured by {@link
 * dev.vertique.examples.restclient.MockConfig}) and the exercise that drives the client both live in
 * {@code UserClientIT}: the test stands up the mock on a separate {@code Vertx}, points the client's
 * base URL at the mock's ephemeral port, boots the application via {@code VertiqueAppExtension}, and
 * asserts the {@code UserClient} operations (list, findById, create, delete) round-trip correctly.
 */
package dev.vertique.examples.restclient;
