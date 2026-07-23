// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.localization;

import dagger.Component;
import dev.vertique.application.VertiqueApp;
import dev.vertique.application.VertiqueApplicationComponent;
import dev.vertique.config.parser.ConfigParsingModule;
import dev.vertique.core.VertxModule;
import dev.vertique.core.lifecycle.CoreLifecycleStepsModule;
import dev.vertique.examples.localization.resource.GeneratedJaxRsResourcesModule;
import dev.vertique.examples.localization.service.GeneratedServicesModule;
import dev.vertique.management.ManagementModule;
import dev.vertique.rest.jaxrs.RestModule;
import dev.vertique.rest.localization.RestLocalizationModule;
import dev.vertique.rest.validation.RestValidationModule;
import dev.vertique.services.DispatchModule;
import jakarta.inject.Singleton;

/**
 * Root Dagger component for the example-localization application.
 *
 * <p>The component is annotated {@link VertiqueApp} and {@code extends}
 * {@link VertiqueApplicationComponent}, so the framework's {@code vertique-codegen-application}
 * annotation processor generates {@code AppComponentVertiqueComponentFactory} (plus its
 * {@code META-INF/services} registration) and the host-neutral lifecycle runner
 * ({@code VertiqueApplicationBootstrap}) drives startup and shutdown — there is no hand-written
 * {@code MainVerticle}. The inherited {@code startupSteps()}/{@code shutdownSteps()}/
 * {@code verticleDeploymentManager()} accessors expose the lifecycle inputs the runner consumes.
 *
 * <p>The runner reproduces the old {@code MainVerticle} choreography exactly through lifecycle
 * phases: Jackson configuration runs as the {@code CONFIGURE}-phase step contributed by
 * {@link CoreLifecycleStepsModule}; the {@code INFRA}-phase verticle (management) deploys; the
 * {@code SERVICES}-phase {@code ServiceDeploymentStartupStep} contributed by {@link DispatchModule}
 * runs {@code serviceDeploymentManager().deployAll()} before any {@code SERVICES}-phase verticle;
 * then the {@code EDGE}-phase verticle (HTTP) deploys. This preserves the legacy
 * {@code INFRA} &rarr; service-dispatch-deploy &rarr; {@code EDGE} ordering.
 *
 * <p>Includes:
 * <ul>
 *   <li>{@link VertxModule} — Vert.x instance and configuration</li>
 *   <li>{@link RestModule} — JAX-RS annotation-driven routing</li>
 *   <li>{@link RestValidationModule} — default {@code web-validation} request-validation strategy</li>
 *   <li>{@link RestLocalizationModule} — inbound locale negotiation; contributes
 *       {@link dev.vertique.rest.localization.RequestLocaleInterceptor} and the built-in
 *       {@link dev.vertique.rest.localization.AcceptLanguageLocaleSource}; transitively includes
 *       {@link dev.vertique.localization.LocalizationModule}</li>
 *   <li>{@link DispatchModule} — Event bus service dispatch infrastructure (includes DeployerModule);
 *       also contributes the paired {@code SERVICES}-phase service deploy/undeploy lifecycle steps</li>
 *   <li>{@link ManagementModule} — Health check endpoints on management port</li>
 *   <li>{@link CoreLifecycleStepsModule} — framework {@code CONFIGURE}/{@code VALIDATE} lifecycle
 *       steps (Jackson configuration + compose-validator harness)</li>
 *   <li>{@link AppModule} — Application-specific configuration bindings and custom
 *       {@link QueryParamLocaleSource} contribution</li>
 *   <li>{@link GeneratedJaxRsResourcesModule} — auto-generated {@code @JaxRsResources} bindings
 *       produced by {@code vertique-codegen-jaxrs} at compile time</li>
 *   <li>{@link GeneratedServicesModule} — auto-generated {@code ServiceContractContributor}
 *       bindings produced by {@code vertique-codegen-services} at compile time</li>
 *   <li>{@link ServiceModule} — typed service client proxy binding</li>
 * </ul>
 *
 * <p>Security modules ({@code AuthModule}, {@code SecurityModule}) are
 * intentionally excluded — this example focuses on locale negotiation and service dispatch.
 */
@VertiqueApp
@Singleton
@Component(
        modules = {
            VertxModule.class,
            ConfigParsingModule.class,
            RestModule.class,
            RestValidationModule.class,
            RestLocalizationModule.class,
            DispatchModule.class,
            ManagementModule.class,
            CoreLifecycleStepsModule.class,
            AppModule.class,
            GeneratedJaxRsResourcesModule.class,
            GeneratedServicesModule.class,
            ServiceModule.class
        })
interface AppComponent extends VertiqueApplicationComponent {}
