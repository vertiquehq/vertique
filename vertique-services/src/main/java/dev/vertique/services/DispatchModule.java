// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services;

import dagger.BindsOptionalOf;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dagger.multibindings.Multibinds;
import dev.vertique.context.ContextRuntimeModule;
import dev.vertique.context.DispatchEnvelopeBuilder;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.eventbus.EventBusClient;
import dev.vertique.core.extension.OrderedExtension;
import dev.vertique.core.health.HealthCheck;
import dev.vertique.core.health.HealthCheckModule;
import dev.vertique.core.health.Readiness;
import dev.vertique.core.lifecycle.ApplicationShutdownStep;
import dev.vertique.core.lifecycle.ApplicationStartupStep;
import dev.vertique.correlation.CorrelationContextModule;
import dev.vertique.deploy.DeployerModule;
import dev.vertique.deploy.VerticleDeployer;
import dev.vertique.deploy.VerticleSupervisor;
import dev.vertique.logging.LoggingContextModule;
import dev.vertique.resilience.dagger.ResilienceModule;
import dev.vertique.security.authz.ActionRegistry;
import dev.vertique.security.authz.Authorizer;
import dev.vertique.security.runtime.IdentitySnapshotDegradationPolicy;
import dev.vertique.security.runtime.events.SecurityEventEmitter;
import dev.vertique.security.runtime.events.SecurityEventsModule;
import dev.vertique.services.config.ServiceConfig;
import dev.vertique.services.config.ServicesConfig;
import dev.vertique.services.dispatch.ServiceMethodMeta;
import dev.vertique.services.interceptor.ServiceAuthorizationInterceptor;
import dev.vertique.services.interceptor.ServiceInterceptor;
import dev.vertique.services.interceptor.SnapshotDegradationGate;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Dagger module providing all service dispatch infrastructure bindings.
 *
 * <p>Include this module in the application {@code @Component} to enable event bus service
 * dispatch. Pair with {@link dev.vertique.core.VertxModule} which provides the {@link Vertx}
 * instance and {@code @VertxConfig JsonObject}.
 *
 * <p>This module transitively includes {@link DeployerModule}, {@link HealthCheckModule}, and
 * {@link SecurityEventsModule}, so applications do not need to include them separately. The
 * {@link SecurityEventsModule} include makes the transport-neutral {@link SecurityEventEmitter}
 * available to the {@link ServiceAuthorizationInterceptor} in every dispatch graph (with an empty
 * observer set by default), so a gated dispatch can always emit its authorization decision event
 * (enforcement-implies-audit). It is harmless for an application to also include
 * {@link SecurityEventsModule} directly (e.g. alongside {@code SecurityAuthzModule}); Dagger
 * deduplicates the module and its {@code @Multibinds} declaration.
 *
 * <p>Extension points via multibinding:
 * <ul>
 *   <li>{@code @Services Set<Object>} — contribute service implementations</li>
 *   <li>{@code Set<ServiceInterceptor>} — contribute cross-cutting service interceptors</li>
 * </ul>
 *
 * <p>Security context propagation is handled automatically by the built-in
 * {@code SecurityContextServiceDispatchEncoder} registered in {@code AuthModule}
 * (in {@code vertique-rest-security}). When a
 * {@link dev.vertique.security.SecurityContext} is bound in the current Vert.x context
 * holder, it is captured into every outgoing dispatch envelope without any application
 * configuration.
 *
 * <p>The {@link ServiceAuthorizationInterceptor} is bound {@code @IntoSet} unconditionally;
 * it takes {@code Optional<Authorizer>} and {@code Optional<ActionRegistry>} so it constructs
 * safely whether or not the core authorization engine ({@code SecurityAuthzModule}) is
 * installed, and a (mandatory, always-present) {@link SecurityEventEmitter} from the included
 * {@link SecurityEventsModule}. When the engine is absent and no service method declares
 * {@code @RequiresAction}, the interceptor is a pass-through no-op. When the engine is absent but a
 * service method does declare {@code @RequiresAction}, construction fails fast (fail-closed
 * invariant). When the engine is present, the interceptor enforces the declared action gates and
 * emits one decision event per gated dispatch (enforcement-implies-audit: the emitter is always
 * available to do so).
 */
@Module(
        includes = {
            HealthCheckModule.class,
            DeployerModule.class,
            ContextRuntimeModule.class,
            LoggingContextModule.class,
            CorrelationContextModule.class,
            ResilienceModule.class,
            SecurityEventsModule.class
        })
public abstract class DispatchModule {

    // --- Multibindings ---

    /**
     * Declares the empty-by-default multibinding for service implementation objects.
     *
     * @return an empty set (applications contribute elements via {@code @IntoSet})
     */
    @Multibinds
    @Services
    abstract Set<Object> services();

    /**
     * Declares the empty-by-default multibinding for service exception mapper customizers.
     *
     * @return an empty set (modules contribute elements via {@code @IntoSet})
     */
    @Multibinds
    abstract Set<ServiceExceptionMapperCustomizer> serviceExceptionMapperCustomizers();

    /**
     * Declares the empty-by-default multibinding for service interceptors.
     *
     * @return an empty set (applications contribute elements via {@code @IntoSet})
     */
    @Multibinds
    abstract Set<ServiceInterceptor> serviceInterceptors();

    // --- Optional authorization engine bindings ---

    /**
     * Declares {@link Authorizer} as an optional binding.
     *
     * <p>This allows {@link ServiceAuthorizationInterceptor} to inject
     * {@code Optional<Authorizer>} without requiring {@code SecurityAuthzModule} to be
     * installed in the component. When the authorization engine is present its
     * {@code @Provides Authorizer} satisfies the optional; when absent the optional is empty
     * and the interceptor is a pass-through no-op (unless a service method declares
     * {@code @RequiresAction}, in which case construction fails fast).
     *
     * @return declared; never called directly
     */
    @BindsOptionalOf
    abstract Authorizer authorizer();

    /**
     * Declares {@link ActionRegistry} as an optional binding.
     *
     * <p>This allows {@link ServiceAuthorizationInterceptor} to inject
     * {@code Optional<ActionRegistry>} without requiring {@code SecurityAuthzModule}.
     * See {@link #authorizer()} for the full rationale.
     *
     * @return declared; never called directly
     */
    @BindsOptionalOf
    abstract ActionRegistry actionRegistry();

    /**
     * Declares {@link IdentitySnapshotDegradationPolicy} as an optional binding.
     *
     * <p>This allows {@link SnapshotDegradationGate} to inject
     * {@code Optional<IdentitySnapshotDegradationPolicy>} without requiring
     * {@code IdentitySnapshotCarriageModule} (identity-snapshot durable carriage) to be installed
     * in the component. When carriage is installed, its {@code @Provides
     * IdentitySnapshotDegradationPolicy} satisfies the optional; when absent the optional is empty
     * and the gate assumes {@link IdentitySnapshotDegradationPolicy#FAIL} — a default that is never
     * actually exercised, since the marker the gate consults is itself only ever bound when
     * carriage is installed.
     *
     * @return declared; never called directly
     */
    @BindsOptionalOf
    abstract IdentitySnapshotDegradationPolicy identitySnapshotDegradationPolicy();

    // --- ServiceMethodMeta set ---

    /**
     * Provides the immutable set of all registered {@link ServiceMethodMeta} instances by
     * extracting them from the {@link ServiceContractRegistry}.
     *
     * <p>The {@link ServiceAuthorizationInterceptor} injects this set at construction time to
     * scan every registered service operation for {@link dev.vertique.security.authz.RequiresAction}
     * declarations and perform startup validation (fail-closed invariant). The provider runs
     * after the registry is fully built, so all operations contributed by code-generated
     * {@code ServiceContractContributor}s are included.
     *
     * @param registry the fully built service contract registry; must not be {@code null}
     * @return an immutable set of all registered service operation metas; never {@code null}
     */
    @Provides
    @Singleton
    static Set<ServiceMethodMeta> serviceMethodMetas(ServiceContractRegistry registry) {
        return registry.entries().stream()
                .flatMap(entry -> entry.operations().values().stream())
                .collect(Collectors.toUnmodifiableSet());
    }

    // --- Built-in interceptor contributions ---

    /**
     * Contributes {@link ServiceAuthorizationInterceptor} into the {@link ServiceInterceptor}
     * multibinding set.
     *
     * <p>The interceptor enforces {@link dev.vertique.security.authz.RequiresAction} gates
     * on service dispatch in the {@link dev.vertique.core.extension.ExtensionPhase#SYSTEM_FIRST}
     * phase. It is always contributed; whether it enforces depends on whether the authorization
     * engine ({@code Optional<Authorizer>}) is present at component construction time.
     *
     * @param i the singleton interceptor; provided by Dagger via its {@code @Inject} constructor
     * @return the interceptor cast to the SPI type
     */
    @Provides
    @IntoSet
    static ServiceInterceptor serviceAuthorizationInterceptor(ServiceAuthorizationInterceptor i) {
        return i;
    }

    /**
     * Contributes {@link SnapshotDegradationGate} into the {@link ServiceInterceptor}
     * multibinding set.
     *
     * <p>The gate enforces the configured identity-snapshot degradation policy in the
     * {@link dev.vertique.core.extension.ExtensionPhase#SYSTEM_FIRST} phase, at a priority earlier
     * than {@link #serviceAuthorizationInterceptor(ServiceAuthorizationInterceptor)} so a
     * {@code FAIL} verdict preempts the action gate. It is always contributed; it is a no-op
     * whenever no {@code SnapshotDegradationMarker} is bound (the overwhelmingly common path, and
     * the only possible path when identity-snapshot durable carriage is not installed).
     *
     * @param i the singleton gate; provided by Dagger via its {@code @Inject} constructor
     * @return the gate cast to the SPI type
     */
    @Provides
    @IntoSet
    static ServiceInterceptor snapshotDegradationGate(SnapshotDegradationGate i) {
        return i;
    }

    /**
     * Declares the empty-by-default multibinding for service contract contributors.
     *
     * <p>Modules that need to register service endpoints outside the standard
     * {@code @ServiceContract} scanning (e.g., dynamically discovered job handlers) contribute
     * entries via {@code @IntoSet @Provides ServiceContractContributor}.
     *
     * @return an empty set (modules contribute elements via {@code @IntoSet})
     */
    @Multibinds
    abstract Set<ServiceContractContributor> serviceContractContributors();

    // --- Typed Config Boundary ---

    /**
     * Parses the {@code services} configuration section into the typed, validated
     * {@link ServicesConfig} at the Dagger provider boundary.
     *
     * <p>This is the only place the raw {@link VertxConfig @VertxConfig JsonObject} is read for the
     * services section into typed config. The external keyed-object shape
     * ({@code services.contracts.{namespace}.{name}}, operations under {@code operations.{operation}})
     * is preserved; the namespace-group key (with {@code _} mapping to the empty default namespace)
     * and the service-name key are injected into the {@link ServiceConfig} identity fields, and each
     * service's compact constructor validates them. Malformed config fails fast here at startup.
     *
     * @param config the application configuration
     * @param parser the injected config parser
     * @return the parsed, validated services config
     */
    @Provides
    @Singleton
    static ServicesConfig servicesConfig(@VertxConfig JsonObject config, ConfigParser parser) {
        return ServicesConfig.fromConfig(config, parser);
    }

    /**
     * Provides the immutable {@code (namespace, name) -> ServiceConfig} lookup index built after
     * per-record validation.
     *
     * @param servicesConfig the parsed, validated services config
     * @return the immutable service index keyed by {@link ServicesConfig.ServiceKey}
     */
    @Provides
    @Singleton
    static Map<ServicesConfig.ServiceKey, ServiceConfig> serviceConfigIndex(ServicesConfig servicesConfig) {
        return servicesConfig.index();
    }

    // --- Singleton Providers ---

    /**
     * Provides the singleton {@link ServiceContractRegistry} built from all registered services
     * and external contributors.
     *
     * @param services the set of service implementation objects from multibinding
     * @param contributors external contributors providing additional contract entries
     * @param config the application configuration forwarded to contributors for their own deployment
     *     option resolution
     * @param serviceConfigIndex the typed {@code (namespace, name) -> ServiceConfig} index supplying this
     *     registry's own deployment-option overrides
     * @return the built service contract registry
     */
    @Provides
    @Singleton
    static ServiceContractRegistry registry(
            @Services Set<Object> services,
            Set<ServiceContractContributor> contributors,
            @VertxConfig JsonObject config,
            Map<ServicesConfig.ServiceKey, ServiceConfig> serviceConfigIndex,
            dev.vertique.services.resilience.ServiceResilienceConfigAdapter resilienceConfigAdapter) {
        ServiceContractRegistry registry =
                ServiceContractRegistry.build(services, contributors, config, serviceConfigIndex);
        resilienceConfigAdapter.validate(registry.entries());
        return registry;
    }

    /**
     * Provides the singleton {@link ServiceTargetResolver} backed by the registry target index.
     *
     * @param registry the fully built service contract registry
     * @return the target resolver
     */
    @Provides
    @Singleton
    static ServiceTargetResolver serviceTargetResolver(ServiceContractRegistry registry) {
        return new DefaultServiceTargetResolver(registry);
    }

    /**
     * Provides the singleton {@link ServiceSupervisor} adapter that delegates restart logic to
     * the underlying {@link VerticleSupervisor}.
     *
     * @param verticleSupervisor the generic verticle supervisor from the deploy module
     * @param serviceConfigIndex the typed {@code (namespace, name) -> ServiceConfig} index for per-service
     *     supervision overrides
     * @return the supervisor adapter instance
     */
    @Provides
    @Singleton
    static ServiceSupervisor supervisor(
            VerticleSupervisor verticleSupervisor, Map<ServicesConfig.ServiceKey, ServiceConfig> serviceConfigIndex) {
        return new ServiceSupervisor(verticleSupervisor, serviceConfigIndex);
    }

    /**
     * Provides the singleton {@link ServiceExceptionMapper} assembled from all registered
     * {@link ServiceExceptionMapperCustomizer} contributions.
     *
     * <p>Customizers are applied in {@link OrderedExtension} order — phase ascending, then
     * {@link ServiceExceptionMapperCustomizer#priority()} ascending, then
     * {@link OrderedExtension#orderKey()} as a stable tie-break.
     *
     * @param customizers the set of customizers from the Dagger multibinding
     * @return the assembled exception mapper
     */
    @Provides
    @Singleton
    static ServiceExceptionMapper serviceExceptionMapper(Set<ServiceExceptionMapperCustomizer> customizers) {
        ServiceExceptionMapper mapper = new ServiceExceptionMapper();
        customizers.stream().sorted(OrderedExtension.comparator()).forEach(c -> c.customize(mapper));
        return mapper;
    }

    /**
     * Provides the singleton {@link ServiceDeploymentManager} that deploys all service verticles.
     *
     * @param vertx the Vert.x instance for registering codecs and availability checks
     * @param deployer the verticle deployer for deployment lifecycle management
     * @param registry the service contract registry
     * @param supervisor the supervisor for registration and availability tracking
     * @param exceptionMapper the exception mapper for failure translation
     * @param interceptors the service interceptors from multibinding
     * @param serviceConfigIndex the typed {@code (namespace, name) -> ServiceConfig} index for per-operation
     *     policy overrides
     * @return the deployment manager
     */
    @Provides
    @Singleton
    static ServiceDeploymentManager deploymentManager(
            Vertx vertx,
            VerticleDeployer deployer,
            ServiceContractRegistry registry,
            ServiceSupervisor supervisor,
            ServiceExceptionMapper exceptionMapper,
            Set<ServiceInterceptor> interceptors,
            Map<ServicesConfig.ServiceKey, ServiceConfig> serviceConfigIndex,
            dev.vertique.context.ServiceDispatchContextRegistry contextRegistry,
            dev.vertique.context.InboundDispatchScope inboundScope,
            dev.vertique.context.InboundExecutionContextScope inboundExecScope,
            dev.vertique.services.resilience.ServiceResiliencePipelineFactory resiliencePipelineFactory) {
        return new ServiceDeploymentManager(
                vertx,
                deployer,
                registry,
                supervisor,
                exceptionMapper,
                interceptors,
                serviceConfigIndex,
                contextRegistry,
                inboundScope,
                inboundExecScope,
                resiliencePipelineFactory);
    }

    // --- Paired SERVICES-phase lifecycle steps (FR-APP-028) ---

    /**
     * Contributes the {@link ServiceDeploymentStartupStep} into the
     * {@code Set<ApplicationStartupStep>} multibinding.
     *
     * <p>The step deploys all service verticles via {@link ServiceDeploymentManager#deployAll()} in
     * the {@link dev.vertique.core.lifecycle.LifecyclePhase#SERVICES SERVICES} phase, at a very low
     * priority so it runs before any {@code SERVICES}-phase verticle (FR-APP-022 / AC-11). It is
     * paired with {@link #serviceDeploymentShutdownStep(ServiceDeploymentShutdownStep)} for
     * owner-managed, deregister-first teardown (FR-APP-028 / AC-14).
     *
     * <p><strong>{@code deployAll()} is NOT idempotent:</strong> a second invocation fails on
     * duplicate verticle names and rolls back, undeploying the already-live services. The runner's
     * {@code SERVICES} step must therefore be the <em>single</em> deploy path. During the additive
     * migration window these contributions are inert until the lifecycle runner consumes them, so an
     * application that still calls {@code serviceDeploymentManager().deployAll()} by hand and does not
     * yet use the runner is unaffected — but the manual call <strong>must be removed</strong> when the
     * example {@code MainVerticle}(s) migrate to the runner (Phase 5), or the runner's deploy and the
     * leftover manual deploy will collide and roll back the live services.
     *
     * @param step the service deployment startup step
     * @return the step as an {@link ApplicationStartupStep}
     */
    @Provides
    @IntoSet
    static ApplicationStartupStep serviceDeploymentStartupStep(ServiceDeploymentStartupStep step) {
        return step;
    }

    /**
     * Contributes the {@link ServiceDeploymentShutdownStep} into the
     * {@code Set<ApplicationShutdownStep>} multibinding.
     *
     * <p>The step undeploys all service verticles via {@link ServiceDeploymentManager#undeployAll()}
     * (deregistering supervision first) during reverse-order teardown, before any generic verticle
     * undeploy (FR-APP-028 / AC-14). It is paired with
     * {@link #serviceDeploymentStartupStep(ServiceDeploymentStartupStep)}.
     *
     * @param step the service deployment shutdown step
     * @return the step as an {@link ApplicationShutdownStep}
     */
    @Provides
    @IntoSet
    static ApplicationShutdownStep serviceDeploymentShutdownStep(ServiceDeploymentShutdownStep step) {
        return step;
    }

    /**
     * Provides the singleton {@link ServiceRequestSender} for low-level event bus request dispatch.
     *
     * <p>Shared by both {@link ServiceClientFactory} (typed proxy path) and the transactional
     * messaging SERVICE adapter to ensure consistent transport behavior.
     *
     * @param eventBusClient the event bus client for transport
     * @param supervisor     the supervisor for availability checks
     * @param servicesConfig the typed services config supplying the global send timeout
     * @param serviceConfigIndex the typed {@code (namespace, name) -> ServiceConfig} index for per-service
     *     and per-operation send timeout overrides
     * @return the request sender
     */
    @Provides
    @Singleton
    static ServiceRequestSender requestSender(
            EventBusClient eventBusClient,
            ServiceSupervisor supervisor,
            dev.vertique.services.resilience.ServiceResilienceConfigAdapter resilienceConfigAdapter) {
        return new ServiceRequestSender(eventBusClient, supervisor, resilienceConfigAdapter);
    }

    /**
     * Provides the singleton {@link ServiceClientFactory} for creating event bus proxy clients.
     *
     * @param sender   the request sender for event bus dispatch
     * @param registry the service contract registry for operation lookup
     * @param builder  the envelope builder for constructing outgoing dispatch envelopes
     * @return the client factory
     */
    @Provides
    @Singleton
    static ServiceClientFactory clientFactory(
            ServiceRequestSender sender, ServiceContractRegistry registry, DispatchEnvelopeBuilder builder) {
        return new ServiceClientFactory(sender, registry, builder);
    }

    /**
     * Contributes the service supervisor health check as a readiness indicator.
     *
     * @param check the service supervisor health check
     * @return the health check instance for readiness multibinding
     */
    @Provides
    @IntoSet
    @Readiness
    static HealthCheck servicesHealthCheck(ServiceSupervisorHealthCheck check) {
        return check;
    }
}
