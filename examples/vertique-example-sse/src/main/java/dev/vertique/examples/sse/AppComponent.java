// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.sse;

import dagger.Component;
import dev.vertique.application.VertiqueApp;
import dev.vertique.application.VertiqueApplicationComponent;
import dev.vertique.config.parser.ConfigParsingModule;
import dev.vertique.core.VertxModule;
import dev.vertique.core.lifecycle.CoreLifecycleStepsModule;
import dev.vertique.deploy.DeployerModule;
import dev.vertique.examples.sse.job.JobModule;
import dev.vertique.management.ManagementModule;
import dev.vertique.rest.jaxrs.RestModule;
import dev.vertique.rest.validation.RestValidationModule;
import jakarta.inject.Singleton;

/**
 * Root Dagger component that wires together all modules for the SSE example application.
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
 *   <li>{@link ManagementModule} — Health check endpoints on management port</li>
 *   <li>{@link DeployerModule} — Verticle deployment multibinding</li>
 *   <li>{@link CoreLifecycleStepsModule} — framework {@code CONFIGURE}/{@code VALIDATE} lifecycle
 *       steps (Jackson configuration + compose-validator harness)</li>
 *   <li>{@link AppModule} — Application-specific verticle deployments and null security bindings</li>
 *   <li>{@link JobModule} — Job service and JAX-RS resource multibinding</li>
 * </ul>
 *
 * <p>Security modules ({@code AuthModule}, {@code SecurityModule}) are intentionally excluded —
 * this example is unauthenticated. {@code SecurityRuntime} is consumed as
 * {@code Optional<SecurityRuntime>} and resolves to {@code Optional.empty()} via
 * {@code RestCoreModule}'s {@code @BindsOptionalOf} when no security module is on the component;
 * {@code AppModule} provides only the {@code @Nullable SecurityPolicyValidator} stand-in.
 */
@VertiqueApp
@Singleton
@Component(
        modules = {
            VertxModule.class,
            ConfigParsingModule.class,
            RestModule.class,
            RestValidationModule.class,
            ManagementModule.class,
            DeployerModule.class,
            CoreLifecycleStepsModule.class,
            AppModule.class,
            JobModule.class
        })
interface AppComponent extends VertiqueApplicationComponent {}
