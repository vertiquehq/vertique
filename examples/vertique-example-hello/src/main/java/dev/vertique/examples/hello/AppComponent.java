// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.hello;

import dagger.Component;
import dev.vertique.application.VertiqueApp;
import dev.vertique.application.VertiqueApplicationComponent;
import dev.vertique.config.parser.ConfigParsingModule;
import dev.vertique.core.VertxModule;
import dev.vertique.core.lifecycle.CoreLifecycleStepsModule;
import dev.vertique.deploy.DeployerModule;
import dev.vertique.examples.hello.resource.GeneratedAopModule;
import dev.vertique.examples.hello.resource.GeneratedJaxRsResourcesModule;
import dev.vertique.examples.hello.resource.ResourceModule;
import dev.vertique.management.ManagementModule;
import dev.vertique.ratelimit.aop.RateLimitAopModule;
import dev.vertique.rest.auth.jwt.JwtAuthModule;
import dev.vertique.rest.jaxrs.RestModule;
import dev.vertique.rest.openapi.validation.OpenApiContractValidationModule;
import dev.vertique.rest.ratelimit.RestRateLimitModule;
import dev.vertique.rest.security.VertxAuthorizationImportModule;
import dev.vertique.rest.validation.RestValidationModule;
import dev.vertique.security.runtime.authz.SecurityAuthzModule;
import jakarta.inject.Singleton;

/**
 * Root Dagger component that wires together all modules for the example-hello application.
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
 *   <li>{@link OpenApiContractValidationModule} — opt-in {@code openapi-contract} strategy, selected
 *       by this application's {@code jaxrs.validationStrategy} config so requests are validated
 *       against the generated {@code openapi.json} (required for the {@code vertique-strict}
 *       decimal-string request wire form; see {@code JsonProfilesDemoResource})</li>
 *   <li>{@link JwtAuthModule} — JWT bearer authentication, authorization, and security context</li>
 *   <li>{@link VertxAuthorizationImportModule} — opt-in import of Vert.x
 *       {@code AuthorizationProvider} grants (e.g. {@link ExampleTeamAuthorizationProvider},
 *       contributed by {@link AppModule}) into the framework's authorization claims</li>
 *   <li>{@link ManagementModule} — Health check endpoints on management port</li>
 *   <li>{@link DeployerModule} — Verticle deployment multibinding</li>
 *   <li>{@link CoreLifecycleStepsModule} — framework {@code CONFIGURE}/{@code VALIDATE} lifecycle
 *       steps (Jackson configuration + compose-validator harness)</li>
 *   <li>{@link SecurityAuthzModule} — action-policy authorization engine (registry, authorizer,
 *       introspector, and built-in {@code authz.*} actions)</li>
 *   <li>{@link AppModule} — Application-specific configuration and verticle deployments</li>
 *   <li>{@code GeneratedJaxRsResourcesModule} — auto-generated JAX-RS resource registration
 *       (emitted by {@code vertique-codegen-dagger} AutoWireProcessor)</li>
 *   <li>{@link ResourceModule} — non-resource JAX-RS extension bindings</li>
 *   <li>{@link RateLimitAopModule} — {@code @RateLimited} aspect binding</li>
 *   <li>{@link RestRateLimitModule} — the rate-limit runtime plus the {@code 429}/{@code 503}
 *       exception mappers for {@code HelloResource#greetLimited} (T013)</li>
 *   <li>{@code GeneratedAopModule} — auto-generated {@code $AopProxy} substitution for {@link
 *       dev.vertique.examples.hello.resource.HelloResource} (emitted by {@code
 *       vertique-codegen-aop} once any method carries an {@code @Aspect}-family annotation such as
 *       {@code @RateLimited}; plan.md Pre-flight finding 1 — reached only through {@code
 *       Provider.get()})</li>
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
            OpenApiContractValidationModule.class,
            JwtAuthModule.class,
            VertxAuthorizationImportModule.class,
            ManagementModule.class,
            DeployerModule.class,
            CoreLifecycleStepsModule.class,
            SecurityAuthzModule.class,
            AppModule.class,
            ResourceModule.class,
            GeneratedJaxRsResourcesModule.class,
            RateLimitAopModule.class,
            RestRateLimitModule.class,
            GeneratedAopModule.class
        })
interface AppComponent extends VertiqueApplicationComponent {}
