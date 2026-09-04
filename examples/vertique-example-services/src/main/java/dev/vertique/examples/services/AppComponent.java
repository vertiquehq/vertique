// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.services;

import dagger.Component;
import dev.vertique.application.VertiqueApp;
import dev.vertique.application.VertiqueApplicationComponent;
import dev.vertique.cache.aop.CacheAopModule;
import dev.vertique.cache.caffeine.CacheCaffeineModule;
import dev.vertique.config.parser.ConfigParsingModule;
import dev.vertique.core.VertxModule;
import dev.vertique.core.lifecycle.CoreLifecycleStepsModule;
import dev.vertique.examples.services.resource.GeneratedJaxRsResourcesModule;
import dev.vertique.examples.services.security.AuthzEventCollector;
import dev.vertique.examples.services.service.GeneratedAopModule;
import dev.vertique.examples.services.service.GeneratedServicesModule;
import dev.vertique.management.ManagementModule;
import dev.vertique.ratelimit.aop.RateLimitAopModule;
import dev.vertique.resilience.aop.ResilienceAopModule;
import dev.vertique.resilience.dagger.ResilienceModule;
import dev.vertique.resilience.dagger.ResiliencePoliciesModule;
import dev.vertique.rest.auth.jwt.JwtAuthModule;
import dev.vertique.rest.jaxrs.RestModule;
import dev.vertique.rest.ratelimit.RestRateLimitModule;
import dev.vertique.rest.validation.RestValidationModule;
import dev.vertique.security.runtime.authz.SecurityAuthzModule;
import dev.vertique.security.runtime.events.SecurityEventsModule;
import dev.vertique.services.DispatchModule;
import jakarta.inject.Singleton;

/**
 * Root Dagger component for the example-services application.
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
 *   <li>{@link DispatchModule} — Event bus service dispatch infrastructure (includes DeployerModule);
 *       also contributes the paired {@code SERVICES}-phase service deploy/undeploy lifecycle steps</li>
 *   <li>{@link ManagementModule} — Health check endpoints on management port</li>
 *   <li>{@link CoreLifecycleStepsModule} — framework {@code CONFIGURE}/{@code VALIDATE} lifecycle
 *       steps (Jackson configuration + compose-validator harness)</li>
 *   <li>{@link JwtAuthModule} — JWT bearer authentication, authorization, and security context</li>
 *   <li>{@link AppModule} — Application-specific configuration bindings</li>
 *   <li>{@link GeneratedJaxRsResourcesModule} — auto-generated {@code @JaxRsResources} bindings
 *       produced by {@code vertique-codegen-jaxrs} at compile time</li>
 *   <li>{@link GeneratedAopModule} — auto-generated AOP proxy bindings for cache-aware service
 *       handlers</li>
 *   <li>{@link CacheCaffeineModule} — explicit local Caffeine provider composition</li>
 *   <li>{@link CacheAopModule} — cache annotation aspect bindings</li>
 *   <li>{@link GeneratedServicesModule} — auto-generated singleton typed-client and
 *       {@code ServiceContractContributor} bindings produced by
 *       {@code vertique-codegen-services} at compile time. Drives
 *       {@code @ConditionalOnProperty}-based implementation selection between
 *       {@link dev.vertique.examples.services.service.UserServiceHandler} (default) and
 *       {@link dev.vertique.examples.services.service.UserServiceSandbox} (sandbox override)</li>
 *   <li>{@link AuthzModule} — programmatic action/policy/role wiring and a capturing
 *       {@link dev.vertique.security.events.SecurityEventObserver} for the
 *       {@code @RequiresAction} dispatch proof
 *       ({@link dev.vertique.examples.services.service.AuthzProbeService})</li>
 *   <li>{@link RateLimitAopModule} — {@code @RateLimited} aspect binding</li>
 *   <li>{@link RestRateLimitModule} — the rate-limit runtime plus the {@code 429}/{@code 503}
 *       exception mappers, shared by {@link
 *       dev.vertique.examples.services.resource.RateLimitProbeResource}'s programmatic path and
 *       {@link dev.vertique.examples.services.service.RateLimitProbeServiceHandler}'s annotated
 *       path (T013, transport-neutrality proof)</li>
 *   <li>{@link ResilienceModule} — resilience runtime components</li>
 *   <li>{@link ResiliencePoliciesModule} — named resilience policy configuration</li>
 *   <li>{@link ResilienceAopModule} — generated {@code @Resilient} aspect binding</li>
 * </ul>
 *
 * <p>{@link SecurityAuthzModule} (authorization engine only) and
 * {@link SecurityEventsModule} (security event emitter) are included so that the
 * {@link dev.vertique.services.interceptor.ServiceAuthorizationInterceptor} wired by
 * {@link DispatchModule} has a live {@link dev.vertique.security.authz.Authorizer}
 * and can emit {@link dev.vertique.security.events.AuthorizationDecisionEvent}s.
 */
@VertiqueApp
@Singleton
@Component(
        modules = {
            VertxModule.class,
            ConfigParsingModule.class,
            RestModule.class,
            RestValidationModule.class,
            DispatchModule.class,
            ManagementModule.class,
            CoreLifecycleStepsModule.class,
            AppModule.class,
            GeneratedJaxRsResourcesModule.class,
            GeneratedAopModule.class,
            CacheCaffeineModule.class,
            CacheAopModule.class,
            GeneratedServicesModule.class,
            JwtAuthModule.class,
            SecurityAuthzModule.class,
            SecurityEventsModule.class,
            AuthzModule.class,
            RateLimitAopModule.class,
            RestRateLimitModule.class,
            ResilienceModule.class,
            ResiliencePoliciesModule.class,
            ResilienceAopModule.class,
            CompositionObserverModule.class
        })
interface AppComponent extends VertiqueApplicationComponent {

    /**
     * Exposes the generated AOP proxy for the resilience probe so the integration test can invoke
     * it directly without adding another transport or client to the example.
     *
     * @return the Dagger-provided resilience probe proxy
     */
    dev.vertique.examples.services.service.ResilienceProbeServiceHandler resilienceProbeServiceHandler();

    /**
     * Exposes the generated AOP proxy for the composition probe.
     *
     * @return the Dagger-provided composition probe proxy
     */
    dev.vertique.examples.services.service.CompositionProbeServiceHandler compositionProbeServiceHandler();

    /**
     * Exposes the ordered event sequence used by the composition characterization.
     *
     * @return the shared composition event collector
     */
    CompositionEventCollector compositionEventCollector();

    /**
     * Exposes the shared {@link AuthzEventCollector} so an integration test can read the
     * authorization decision events and guarded-handler invocations recorded while dispatching the
     * {@link dev.vertique.examples.services.service.AuthzProbeService} operation through the real,
     * Dagger-wired services pipeline.
     *
     * @return the singleton authorization event/invocation collector
     */
    AuthzEventCollector authzEventCollector();
}
