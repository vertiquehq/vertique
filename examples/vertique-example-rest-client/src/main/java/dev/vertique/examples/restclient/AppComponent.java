// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.restclient;

import dagger.Component;
import dev.vertique.application.VertiqueApp;
import dev.vertique.application.VertiqueApplicationComponent;
import dev.vertique.config.parser.ConfigParsingModule;
import dev.vertique.core.VertxModule;
import dev.vertique.core.lifecycle.CoreLifecycleStepsModule;
import dev.vertique.deploy.DeployerModule;
import dev.vertique.examples.restclient.client.GeneratedRestClientsModule;
import dev.vertique.examples.restclient.client.UserClient;
import dev.vertique.rest.client.RestClientModule;
import jakarta.inject.Singleton;

/**
 * Root Dagger component that wires together all modules for the example-rest-client application.
 *
 * <p>The component is annotated {@link VertiqueApp} and {@code extends}
 * {@link VertiqueApplicationComponent}, so the framework's {@code vertique-codegen-application}
 * annotation processor generates {@code AppComponentVertiqueComponentFactory} (plus its
 * {@code META-INF/services} registration) and the host-neutral lifecycle runner
 * ({@code VertiqueApplicationBootstrap}) drives startup and shutdown — there is no hand-written
 * {@code MainVerticle}. The inherited {@code startupSteps()}/{@code shutdownSteps()}/
 * {@code verticleDeploymentManager()} accessors expose the lifecycle inputs the runner consumes.
 * The process JSON codec's mapper is installed in the {@code CONFIGURE} phase by the JSON runtime
 * {@link RestClientModule} brings in.
 *
 * <p>This is a pure REST <em>client</em> application: it deploys no verticles of its own. The
 * {@link UserClient} binding's base URL is resolved from {@code restClient.userService.baseUrl} in
 * the application config (see {@link RestClientModule}). The mock user service it talks to, and the
 * exercise that drives the client, both live in the integration test rather than in this component.
 *
 * <p>Includes:
 * <ul>
 *   <li>{@link VertxModule} — Vert.x instance and {@code @VertxConfig} configuration</li>
 *   <li>{@link RestClientModule} — REST client factory and infrastructure bindings, including the
 *       JSON mapper profile runtime and its {@code CONFIGURE}-phase process-codec install step</li>
 *   <li>{@link DeployerModule} — verticle deployment multibinding plus the empty-by-default
 *       lifecycle-step sets the runner consumes</li>
 *   <li>{@link CoreLifecycleStepsModule} — the framework's {@code VALIDATE} lifecycle step (the
 *       compose-validator harness)</li>
 *   <li>{@link GeneratedRestClientsModule} — auto-generated {@code @Singleton} REST client
 *       bindings produced by {@code AutoWireProcessor} at compile time</li>
 * </ul>
 */
@VertiqueApp
@Singleton
@Component(
        modules = {
            VertxModule.class,
            ConfigParsingModule.class,
            RestClientModule.class,
            DeployerModule.class,
            CoreLifecycleStepsModule.class,
            GeneratedRestClientsModule.class
        })
interface AppComponent extends VertiqueApplicationComponent {

    /**
     * Returns the user client proxy. Exposed so the integration test can drive the client's
     * operations against the mock server it stands up.
     *
     * @return the singleton UserClient proxy
     */
    UserClient userClient();
}
