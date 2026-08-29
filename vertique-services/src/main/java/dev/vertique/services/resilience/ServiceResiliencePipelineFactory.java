// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services.resilience;

import dev.vertique.resilience.Resilience;
import dev.vertique.resilience.ResiliencePipeline;
import dev.vertique.resilience.ResolvedResiliencePolicy;
import dev.vertique.resilience.adapter.AdapterOperationIdentity;
import dev.vertique.services.dispatch.ServiceMethodMeta;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Builds and caches one common-runtime pipeline per registered Services operation. */
@Singleton
public final class ServiceResiliencePipelineFactory {

    private final ServiceResilienceConfigAdapter configAdapter;
    private final dev.vertique.resilience.adapter.ResilienceAdapterContext context;
    private final ConcurrentMap<ServiceMethodMeta, ResiliencePipeline> pipelines = new ConcurrentHashMap<>();

    @Inject
    public ServiceResiliencePipelineFactory(ServiceResilienceConfigAdapter configAdapter, Resilience resilience) {
        this.configAdapter = configAdapter;
        this.context = resilience.adapterSupport().newContext();
    }

    /** Returns the single common pipeline for an annotated operation, or {@code null} otherwise. */
    public ResiliencePipeline pipeline(ServiceMethodMeta meta) {
        if (!meta.resilienceAnnotations().hasAny()) {
            return null;
        }
        return pipelines.computeIfAbsent(meta, this::build);
    }

    private ResiliencePipeline build(ServiceMethodMeta meta) {
        AdapterOperationIdentity identity =
                new AdapterOperationIdentity("services", List.of(meta.namespace(), meta.name(), meta.operation()));
        ResolvedResiliencePolicy policy = configAdapter.resolve(meta);
        return policy.circuitBreaker().isPresent()
                ? context.pipeline(identity, policy, failure -> true)
                : context.pipeline(identity, policy);
    }

    /** Closes the adapter-owned breaker context and all pipelines it created. */
    public io.vertx.core.Future<Void> close() {
        return context.close();
    }
}
