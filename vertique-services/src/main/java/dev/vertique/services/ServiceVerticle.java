// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services;

import dev.vertique.context.InboundDispatchScope;
import dev.vertique.context.InboundExecutionContextScope;
import dev.vertique.context.ServiceDispatchContextRegistry;
import dev.vertique.resilience.ResiliencePipeline;
import dev.vertique.services.dispatch.ServiceMethodInvoker;
import dev.vertique.services.dispatch.ServiceMethodMeta;
import dev.vertique.services.interceptor.ServiceInterceptor;
import dev.vertique.services.resilience.ServiceResiliencePipelineFactory;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * Verticle that registers event bus consumers for all operations of a single service contract.
 *
 * <p>Each service deploys in its own verticle instance for actor-like isolation. Codecs are
 * NOT registered here — {@link ServiceDeploymentManager} handles codec registration before
 * deploying any verticles.
 *
 * <p>At start, one event bus consumer is registered per operation. Consumer registration is
 * synchronous for the local event bus, so {@link #start(Promise)} completes immediately after
 * registering all consumers.
 *
 * @param <T> the contract interface type
 */
@Slf4j
public class ServiceVerticle<T> extends AbstractVerticle {

    private final ServiceContractRegistry.ContractEntry<T> entry;
    private final ServiceExceptionMapper exceptionMapper;
    private final List<ServiceInterceptor> interceptors;
    private final ServiceResiliencePipelineFactory resiliencePipelineFactory;
    private final ServiceMethodInvoker.FatalErrorHandler fatalErrorHandler;
    private final ServiceDispatchContextRegistry contextRegistry;
    private final InboundDispatchScope inboundScope;
    private final InboundExecutionContextScope inboundExecScope;

    /**
     * Creates a new service verticle for the given contract entry.
     *
     * @param entry the contract entry describing the service, its implementation, and operations
     * @param exceptionMapper exception mapper used by each {@link ServiceMethodInvoker}
     * @param interceptors service interceptors in {@link dev.vertique.core.extension.OrderedExtension}
     *                     order (phase → priority → orderKey)
     * @param resiliencePipelineFactory factory that constructs per-operation policy pipelines
     * @param fatalErrorHandler optional handler for non-recoverable {@link Error} instances;
     *                          may be {@code null}
     */
    public ServiceVerticle(
            ServiceContractRegistry.ContractEntry<T> entry,
            ServiceExceptionMapper exceptionMapper,
            List<ServiceInterceptor> interceptors,
            ServiceResiliencePipelineFactory resiliencePipelineFactory,
            ServiceMethodInvoker.FatalErrorHandler fatalErrorHandler) {
        this(entry, exceptionMapper, interceptors, resiliencePipelineFactory, fatalErrorHandler, null);
    }

    /**
     * Creates a new service verticle with an explicit
     * {@link ServiceDispatchContextRegistry} for decoder-driven inbound carrier validation.
     *
     * @param contextRegistry the service-dispatch context registry; pass {@code null} to disable
     *                        decoder-driven validation (legacy passthrough)
     */
    public ServiceVerticle(
            ServiceContractRegistry.ContractEntry<T> entry,
            ServiceExceptionMapper exceptionMapper,
            List<ServiceInterceptor> interceptors,
            ServiceResiliencePipelineFactory resiliencePipelineFactory,
            ServiceMethodInvoker.FatalErrorHandler fatalErrorHandler,
            ServiceDispatchContextRegistry contextRegistry) {
        this(entry, exceptionMapper, interceptors, resiliencePipelineFactory, fatalErrorHandler, contextRegistry, null);
    }

    /**
     * Constructor including the injected {@link InboundDispatchScope} threaded into each
     * per-operation {@link ServiceMethodInvoker}.
     */
    public ServiceVerticle(
            ServiceContractRegistry.ContractEntry<T> entry,
            ServiceExceptionMapper exceptionMapper,
            List<ServiceInterceptor> interceptors,
            ServiceResiliencePipelineFactory resiliencePipelineFactory,
            ServiceMethodInvoker.FatalErrorHandler fatalErrorHandler,
            ServiceDispatchContextRegistry contextRegistry,
            InboundDispatchScope inboundScope) {
        this(
                entry,
                exceptionMapper,
                interceptors,
                resiliencePipelineFactory,
                fatalErrorHandler,
                contextRegistry,
                inboundScope,
                null);
    }

    /**
     * Full Dagger constructor: adds the substrate {@link InboundExecutionContextScope} lifecycle
     * helper threaded into each per-operation {@link ServiceMethodInvoker} so the dispatch path
     * composes the inbound install with all registered
     * {@link dev.vertique.core.context.InboundContextInitializer}s (e.g.
     * {@code CorrelationContextSeeder}) in one scope. {@code null} is allowed for legacy callers.
     *
     * @param inboundExecScope substrate lifecycle helper, or {@code null} for legacy fallback
     */
    public ServiceVerticle(
            ServiceContractRegistry.ContractEntry<T> entry,
            ServiceExceptionMapper exceptionMapper,
            List<ServiceInterceptor> interceptors,
            ServiceResiliencePipelineFactory resiliencePipelineFactory,
            ServiceMethodInvoker.FatalErrorHandler fatalErrorHandler,
            ServiceDispatchContextRegistry contextRegistry,
            InboundDispatchScope inboundScope,
            InboundExecutionContextScope inboundExecScope) {
        this.entry = entry;
        this.exceptionMapper = exceptionMapper;
        this.interceptors = List.copyOf(interceptors);
        this.resiliencePipelineFactory = resiliencePipelineFactory;
        this.fatalErrorHandler = fatalErrorHandler;
        this.contextRegistry = contextRegistry;
        this.inboundScope = inboundScope;
        this.inboundExecScope = inboundExecScope;
    }

    /**
     * Registers event bus consumers for each operation in the contract entry.
     *
     * <p>Consumers are registered synchronously. The start promise is completed immediately
     * after all consumers are registered.
     *
     * @param startPromise the promise to complete when the verticle has started
     */
    @Override
    public void start(Promise<Void> startPromise) {
        for (ServiceMethodMeta meta : entry.operations().values()) {
            ResiliencePipeline pipeline =
                    resiliencePipelineFactory == null ? null : resiliencePipelineFactory.pipeline(meta);
            ServiceMethodInvoker invoker = new ServiceMethodInvoker(
                    meta,
                    exceptionMapper,
                    interceptors,
                    pipeline,
                    fatalErrorHandler,
                    vertx,
                    contextRegistry,
                    inboundScope,
                    inboundExecScope);
            vertx.eventBus().consumer(meta.address(), invoker);
            log.info(
                    "Registered: {} \u2192 {}.{}(){}",
                    meta.address(),
                    entry.serviceInstance().getClass().getSimpleName(),
                    meta.method().name(),
                    meta.oneWay() ? " [one-way]" : "");
        }
        startPromise.complete();
    }
}
