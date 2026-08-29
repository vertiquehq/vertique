// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services;

import dev.vertique.core.eventbus.LocalMessageCodec;
import dev.vertique.core.extension.OrderedExtension;
import dev.vertique.core.lifecycle.LifecyclePhase;
import dev.vertique.deploy.VerticleDeployer;
import dev.vertique.deploy.VerticleDeployment;
import dev.vertique.services.config.ServiceConfig;
import dev.vertique.services.config.ServicesConfig.ServiceKey;
import dev.vertique.services.dispatch.ServiceMethodInvoker;
import dev.vertique.services.interceptor.ServiceInterceptor;
import dev.vertique.services.resilience.ServiceResiliencePipelineFactory;
import io.vertx.core.Future;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

/**
 * Deploys all service verticles from the contract registry.
 *
 * <p>Registers local event bus codecs ({@code dispatch.envelope} and {@code dispatch.result}) exactly
 * once before deploying any verticles. Uses an {@link AtomicBoolean} guard for idempotency in
 * case of redeployment.
 *
 * <p>Each service is deployed as a separate {@link ServiceVerticle} via the {@link VerticleDeployer}.
 * Deployment options (instance count, worker model) are derived from the {@link ServiceContractRegistry}
 * and the application configuration. If any deployment fails, all successfully deployed services
 * are rolled back.
 *
 * <p>Instances are created by {@link DispatchModule} via a {@code @Provides} method.
 */
@Slf4j
public class ServiceDeploymentManager {

    private final io.vertx.core.Vertx vertx;
    private final VerticleDeployer deployer;
    private final ServiceContractRegistry registry;
    private final ServiceSupervisor supervisor;
    private final ServiceExceptionMapper exceptionMapper;
    private final List<ServiceInterceptor> interceptors;
    private final Map<ServiceKey, ServiceConfig> serviceConfigIndex;
    private final ServiceResiliencePipelineFactory resiliencePipelineFactory;
    private final dev.vertique.context.ServiceDispatchContextRegistry contextRegistry;
    private final dev.vertique.context.InboundDispatchScope inboundScope;
    private final dev.vertique.context.InboundExecutionContextScope inboundExecScope;
    private final AtomicBoolean codecsRegistered = new AtomicBoolean(false);
    private final Map<Class<?>, String> deploymentNames = new ConcurrentHashMap<>();

    /**
     * Creates a new deployment manager.
     *
     * @param vertx the Vert.x instance used for deploying verticles and registering codecs
     * @param deployer the verticle deployer for deployment lifecycle management
     * @param registry the service contract registry providing all contract entries
     * @param supervisor the supervisor tracking deployment names and restart budget
     * @param exceptionMapper exception mapper passed to each {@link ServiceMethodInvoker}
     * @param interceptors service interceptors contributed via Dagger multibinding
     * @param serviceConfigIndex the typed {@code (namespace, name) -> ServiceConfig} index for per-operation
     *     policy overrides
     */
    public ServiceDeploymentManager(
            io.vertx.core.Vertx vertx,
            VerticleDeployer deployer,
            ServiceContractRegistry registry,
            ServiceSupervisor supervisor,
            ServiceExceptionMapper exceptionMapper,
            Set<ServiceInterceptor> interceptors,
            Map<ServiceKey, ServiceConfig> serviceConfigIndex) {
        this(vertx, deployer, registry, supervisor, exceptionMapper, interceptors, serviceConfigIndex, null);
    }

    /**
     * Full constructor including the optional service-dispatch context registry threaded into
     * each {@link ServiceVerticle} for decoder-driven inbound carrier validation
     * (FR-CTX-072..076).
     *
     * @param contextRegistry the service-dispatch context registry, or {@code null} to disable
     *                        decoder-driven validation (legacy passthrough)
     */
    public ServiceDeploymentManager(
            io.vertx.core.Vertx vertx,
            VerticleDeployer deployer,
            ServiceContractRegistry registry,
            ServiceSupervisor supervisor,
            ServiceExceptionMapper exceptionMapper,
            Set<ServiceInterceptor> interceptors,
            Map<ServiceKey, ServiceConfig> serviceConfigIndex,
            dev.vertique.context.ServiceDispatchContextRegistry contextRegistry) {
        this(
                vertx,
                deployer,
                registry,
                supervisor,
                exceptionMapper,
                interceptors,
                serviceConfigIndex,
                contextRegistry,
                null);
    }

    /**
     * Constructor including the injected {@link dev.vertique.context.InboundDispatchScope} for
     * decoder-driven inbound-dispatch context install. Pass {@code null} to fall back to the
     * framework's static helpers (used by legacy tests that bypass Dagger).
     */
    public ServiceDeploymentManager(
            io.vertx.core.Vertx vertx,
            VerticleDeployer deployer,
            ServiceContractRegistry registry,
            ServiceSupervisor supervisor,
            ServiceExceptionMapper exceptionMapper,
            Set<ServiceInterceptor> interceptors,
            Map<ServiceKey, ServiceConfig> serviceConfigIndex,
            dev.vertique.context.ServiceDispatchContextRegistry contextRegistry,
            dev.vertique.context.InboundDispatchScope inboundScope) {
        this(
                vertx,
                deployer,
                registry,
                supervisor,
                exceptionMapper,
                interceptors,
                serviceConfigIndex,
                contextRegistry,
                inboundScope,
                null,
                null);
    }

    /**
     * Full Dagger constructor: adds the substrate
     * {@link dev.vertique.context.InboundExecutionContextScope} lifecycle helper threaded into
     * each {@code ServiceVerticle} so the dispatch path composes the inbound install with all
     * registered {@link dev.vertique.core.context.InboundContextInitializer}s (e.g.
     * {@code CorrelationContextSeeder}) in one scope. Pass {@code null} to fall back to the
     * plain inbound-scope install.
     *
     * @param inboundExecScope substrate lifecycle helper, or {@code null} for legacy fallback
     */
    public ServiceDeploymentManager(
            io.vertx.core.Vertx vertx,
            VerticleDeployer deployer,
            ServiceContractRegistry registry,
            ServiceSupervisor supervisor,
            ServiceExceptionMapper exceptionMapper,
            Set<ServiceInterceptor> interceptors,
            Map<ServiceKey, ServiceConfig> serviceConfigIndex,
            dev.vertique.context.ServiceDispatchContextRegistry contextRegistry,
            dev.vertique.context.InboundDispatchScope inboundScope,
            dev.vertique.context.InboundExecutionContextScope inboundExecScope) {
        this(
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
                null);
    }

    /** Full constructor with the application-scoped common resilience pipeline factory. */
    public ServiceDeploymentManager(
            io.vertx.core.Vertx vertx,
            VerticleDeployer deployer,
            ServiceContractRegistry registry,
            ServiceSupervisor supervisor,
            ServiceExceptionMapper exceptionMapper,
            Set<ServiceInterceptor> interceptors,
            Map<ServiceKey, ServiceConfig> serviceConfigIndex,
            dev.vertique.context.ServiceDispatchContextRegistry contextRegistry,
            dev.vertique.context.InboundDispatchScope inboundScope,
            dev.vertique.context.InboundExecutionContextScope inboundExecScope,
            ServiceResiliencePipelineFactory resiliencePipelineFactory) {
        this.vertx = vertx;
        this.deployer = deployer;
        this.registry = registry;
        this.supervisor = supervisor;
        this.exceptionMapper = exceptionMapper;
        this.interceptors =
                interceptors.stream().sorted(OrderedExtension.comparator()).collect(Collectors.toUnmodifiableList());
        this.serviceConfigIndex = serviceConfigIndex;
        this.contextRegistry = contextRegistry;
        this.inboundScope = inboundScope;
        this.inboundExecScope = inboundExecScope;
        this.resiliencePipelineFactory = resiliencePipelineFactory;
    }

    /**
     * Deploys all service verticles, registering local codecs first.
     *
     * <p>Codec registration is idempotent — duplicate registration exceptions are silently
     * swallowed. All services are deployed in parallel using {@link Future#join} semantics
     * (wait-all). If any deployment fails, all successfully deployed services are rolled back
     * and the returned future fails with the original cause.
     *
     * @return a future that succeeds when all services are deployed, or fails if any deployment fails
     */
    public Future<Void> deployAll() {
        registerCodecs();
        List<Future<String>> deployments =
                registry.entries().stream().map(this::deployService).collect(Collectors.toList());
        return Future.join(deployments).<Void>mapEmpty().recover(cause -> rollback()
                .transform(v -> Future.failedFuture(cause)));
    }

    /**
     * Undeploys all service verticles, deregistering from the supervisor first to prevent restarts.
     *
     * <p>Each service is deregistered from the supervisor before undeploy to suppress restart
     * attempts. Undeploy is attempted for all services regardless of individual failures
     * ({@link Future#join} semantics). Tracking entries are only removed on successful undeploy;
     * failed entries are retained for inspection or retry.
     *
     * @return a future that succeeds when all services are undeployed
     */
    public Future<Void> undeployAll() {
        List<Future<Void>> undeploys = new ArrayList<>();
        for (Map.Entry<Class<?>, String> entry : deploymentNames.entrySet()) {
            supervisor.deregister(entry.getKey());
            String name = entry.getValue();
            undeploys.add(deployer.undeploy(name)
                    .onSuccess(v -> deploymentNames.remove(entry.getKey()))
                    .onFailure(cause -> log.warn("Failed to undeploy service: {}", name, cause)));
        }
        Future<Void> undeploy = undeploys.isEmpty()
                ? Future.succeededFuture()
                : Future.join(undeploys).mapEmpty();
        if (resiliencePipelineFactory == null) {
            return undeploy;
        }
        return undeploy.compose(ignored -> resiliencePipelineFactory.close())
                .recover(cause -> resiliencePipelineFactory.close().transform(ignored -> Future.failedFuture(cause)));
    }

    // --- Internal ---

    /**
     * Rolls back all currently tracked service deployments. Used internally when
     * {@link #deployAll()} fails to undo any successfully started services.
     *
     * <p>Each service is deregistered from the supervisor to suppress restart attempts.
     * Tracking entries are only removed on successful undeploy; failed entries are retained
     * so the caller can inspect or retry. All undeploys are attempted regardless of individual
     * failures ({@link Future#join} semantics).
     *
     * @return a future that completes when all rollback undeploys have been attempted
     */
    private Future<Void> rollback() {
        List<Future<Void>> undeploys = new ArrayList<>();
        for (Map.Entry<Class<?>, String> entry : deploymentNames.entrySet()) {
            supervisor.deregister(entry.getKey());
            String name = entry.getValue();
            undeploys.add(deployer.undeploy(name)
                    .onSuccess(v -> deploymentNames.remove(entry.getKey()))
                    .onFailure(cause -> log.warn("Rollback: failed to undeploy {}, tracking retained", name, cause)));
        }
        if (undeploys.isEmpty()) {
            return Future.succeededFuture();
        }
        return Future.join(undeploys).mapEmpty();
    }

    /**
     * Builds a deterministic deployment name for a service contract entry.
     *
     * @param entry the contract entry
     * @return the deployment name in format {@code dispatch-service:{namespace}/{name}#{contractFqcn}}
     */
    private static String serviceDeploymentName(ServiceContractRegistry.ContractEntry<?> entry) {
        return "dispatch-service:" + entry.namespace() + "/" + entry.name() + "#"
                + entry.contract().getName();
    }

    /**
     * Deploys a single service as a {@link ServiceVerticle} via the {@link VerticleDeployer}.
     *
     * <p>On successful deployment, registers the deployment name in the manager's tracking map
     * and registers the service with the supervisor using the deployment name and ID so that
     * {@link dev.vertique.deploy.VerticleSupervisor} can handle undeploy and stale-tracking
     * recovery internally on restart.
     *
     * <p>Uses a supplier-based {@link VerticleDeployment} so Vert.x creates a fresh
     * {@link ServiceVerticle} per instance, enabling multi-instance deployment.
     *
     * @param <T> the contract type
     * @param entry the contract entry to deploy
     * @return a future of the Vert.x deployment ID
     */
    private <T> Future<String> deployService(ServiceContractRegistry.ContractEntry<T> entry) {
        String name = serviceDeploymentName(entry);
        ServiceMethodInvoker.FatalErrorHandler fatalHandler =
                error -> supervisor.reportFatalError(entry.contract(), error);

        VerticleDeployment deployment = new VerticleDeployment(
                name,
                () -> new ServiceVerticle<>(
                        entry,
                        exceptionMapper,
                        interceptors,
                        resiliencePipelineFactory,
                        fatalHandler,
                        contextRegistry,
                        inboundScope,
                        inboundExecScope),
                entry.deploymentOptions(),
                LifecyclePhase.SERVICES,
                0);

        return deployer.deploy(deployment)
                .onSuccess(id -> {
                    deploymentNames.put(entry.contract(), name);
                    supervisor.watch(
                            entry.contract(),
                            entry.namespace(),
                            entry.name(),
                            name,
                            id,
                            () -> redeployServiceAsync(entry));
                    log.info(
                            "Deployed service: {} [{}] instances={}",
                            entry.baseAddress(),
                            entry.serviceInstance().getClass().getSimpleName(),
                            entry.deploymentOptions().getInstances());
                })
                .onFailure(cause -> log.error("Failed to deploy service: {}", entry.baseAddress(), cause));
    }

    /**
     * Asynchronously redeploys a service. Called by the supervisor's restart logic.
     *
     * <p>On success, {@link ServiceSupervisor#watch} is called (via {@link #deployService} which
     * deploys through the {@link VerticleDeployer}), resetting the restart-in-progress flag. On
     * failure, {@link ServiceSupervisor#reportRedeployFailure} is called to clear the flag and
     * schedule another attempt if the budget allows.
     *
     * @param <T> the contract type
     * @param entry the contract entry to redeploy
     */
    private <T> void redeployServiceAsync(ServiceContractRegistry.ContractEntry<T> entry) {
        deployService(entry)
                .onSuccess(id -> log.info("Service {} redeployed successfully", entry.baseAddress()))
                .onFailure(cause -> {
                    log.error("Service {} redeploy failed", entry.baseAddress(), cause);
                    supervisor.reportRedeployFailure(entry.contract(), cause);
                });
    }

    /**
     * Registers the local event bus codecs for {@link dev.vertique.core.eventbus.DispatchEnvelope} and
     * {@link dev.vertique.core.Result} if not already registered.
     *
     * <p>Each codec is registered independently. If any codec fails to register for an unexpected
     * reason, the {@link AtomicBoolean} guard is reset so the next {@link #deployAll()} call will
     * retry.
     */
    private void registerCodecs() {
        if (codecsRegistered.compareAndSet(false, true)) {
            boolean success = registerCodec("dispatch.envelope") & registerCodec("dispatch.result");
            if (success) {
                log.debug("Registered local codecs: dispatch.envelope, dispatch.result");
            } else {
                codecsRegistered.set(false);
                log.warn("Codec registration incomplete — will retry on next deployAll()");
            }
        }
    }

    /**
     * Registers a single local codec by name.
     *
     * @param name the codec name to register
     * @return {@code true} if registration succeeded (including already-registered), {@code false}
     *     on unexpected error
     */
    private boolean registerCodec(String name) {
        try {
            vertx.eventBus().registerCodec(new LocalMessageCodec<>(name));
            return true;
        } catch (IllegalStateException e) {
            log.debug("Local codec '{}' already registered", name);
            return true;
        } catch (Exception e) {
            log.error("Failed to register codec '{}': {}", name, e.getMessage(), e);
            return false;
        }
    }
}
