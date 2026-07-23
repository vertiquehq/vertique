// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.customresponse;

import dagger.Component;
import dev.vertique.application.VertiqueApp;
import dev.vertique.application.VertiqueApplicationComponent;
import dev.vertique.config.parser.ConfigParsingModule;
import dev.vertique.core.VertxModule;
import dev.vertique.core.lifecycle.CoreLifecycleStepsModule;
import dev.vertique.deploy.DeployerModule;
import dev.vertique.examples.customresponse.resource.ResourceModule;
import dev.vertique.rest.auth.jwt.JwtAuthModule;
import dev.vertique.rest.jaxrs.RestModule;
import dev.vertique.rest.validation.RestValidationModule;
import dev.vertique.security.runtime.authz.SecurityAuthzModule;
import jakarta.inject.Singleton;

/**
 * Root Dagger component that wires together all modules for the example-custom-response application.
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
 *   <li>{@link DeployerModule} — Verticle deployment multibinding</li>
 *   <li>{@link SecurityAuthzModule} — action-policy authorization engine (registry, authorizer,
 *       introspector, and built-in {@code authz.*} actions)</li>
 *   <li>{@link CoreLifecycleStepsModule} — framework {@code CONFIGURE}/{@code VALIDATE} lifecycle
 *       steps (Jackson configuration + compose-validator harness)</li>
 *   <li>{@link AppModule} — Application-specific configuration and verticle deployments</li>
 *   <li>{@link ResourceModule} — JAX-RS resource registration, custom response producers</li>
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
            DeployerModule.class,
            SecurityAuthzModule.class,
            CoreLifecycleStepsModule.class,
            AppModule.class,
            ResourceModule.class
        })
interface AppComponent extends VertiqueApplicationComponent {}
