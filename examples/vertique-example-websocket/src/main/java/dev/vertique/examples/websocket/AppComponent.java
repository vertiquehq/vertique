// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.websocket;

import dagger.Component;
import dev.vertique.application.VertiqueApp;
import dev.vertique.application.VertiqueApplicationComponent;
import dev.vertique.config.parser.ConfigParsingModule;
import dev.vertique.core.VertxModule;
import dev.vertique.core.lifecycle.CoreLifecycleStepsModule;
import dev.vertique.deploy.DeployerModule;
import dev.vertique.examples.websocket.chat.ChatModule;
import dev.vertique.examples.websocket.resource.ResourceModule;
import dev.vertique.management.ManagementModule;
import dev.vertique.rest.auth.jwt.JwtAuthModule;
import dev.vertique.rest.jaxrs.RestModule;
import dev.vertique.rest.validation.RestValidationModule;
import dev.vertique.rest.websocket.dagger.WebSocketModule;
import dev.vertique.security.runtime.authz.SecurityAuthzModule;
import jakarta.inject.Singleton;

/**
 * Root Dagger component that wires together all modules for the WebSocket chat example.
 *
 * <p>The component is annotated {@link VertiqueApp} and {@code extends}
 * {@link VertiqueApplicationComponent}, so the framework's {@code vertique-codegen-application}
 * annotation processor generates {@code AppComponentVertiqueComponentFactory} (plus its
 * {@code META-INF/services} registration) and the host-neutral lifecycle runner
 * ({@code VertiqueApplicationBootstrap}) drives startup and shutdown — there is no hand-written
 * {@code MainVerticle}. The inherited {@code startupSteps()}/{@code shutdownSteps()}/
 * {@code verticleDeploymentManager()} accessors expose the lifecycle inputs the runner consumes;
 * Jackson configuration runs as the {@code CONFIGURE}-phase step contributed by
 * {@link CoreLifecycleStepsModule}.
 *
 * <p>Includes:
 * <ul>
 *   <li>{@link VertxModule} — Vert.x instance and configuration</li>
 *   <li>{@link RestModule} — JAX-RS annotation-driven routing</li>
 *   <li>{@link RestValidationModule} — default {@code web-validation} request-validation strategy</li>
 *   <li>{@link JwtAuthModule} — JWT bearer authentication, authorization, and security context</li>
 *   <li>{@link WebSocketModule} — WebSocket endpoint registration and upgrade handling</li>
 *   <li>{@link ManagementModule} — Health check endpoints on the management port</li>
 *   <li>{@link DeployerModule} — Verticle deployment multibinding</li>
 *   <li>{@link CoreLifecycleStepsModule} — framework {@code CONFIGURE}/{@code VALIDATE} lifecycle
 *       steps (Jackson configuration + compose-validator harness)</li>
 *   <li>{@link SecurityAuthzModule} — action-policy authorization engine (registry, authorizer,
 *       introspector, and built-in {@code authz.*} actions)</li>
 *   <li>{@link AppModule} — JWT auth wiring and verticle deployment registrations</li>
 *   <li>{@link ChatModule} — Chat endpoint and room registry</li>
 *   <li>{@link ResourceModule} — JAX-RS ping resource</li>
 * </ul>
 */
@VertiqueApp
@Singleton
@Component(
        modules = {
            VertxModule.class,
            ConfigParsingModule.class,
            RestModule.class,
            RestValidationModule.class,
            JwtAuthModule.class,
            WebSocketModule.class,
            ManagementModule.class,
            DeployerModule.class,
            CoreLifecycleStepsModule.class,
            SecurityAuthzModule.class,
            AppModule.class,
            ChatModule.class,
            ResourceModule.class
        })
interface AppComponent extends VertiqueApplicationComponent {}
