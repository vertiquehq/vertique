// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience.aop;

import dev.vertique.aop.AspectProvider;
import dev.vertique.aop.MethodInterceptor;
import dev.vertique.core.codegen.MethodMetadata;
import dev.vertique.resilience.Resilience;
import dev.vertique.resilience.ResilienceDefaults;
import dev.vertique.resilience.ResiliencePipeline;
import dev.vertique.resilience.ResiliencePolicyOverrides;
import dev.vertique.resilience.ResiliencePolicyRegistry;
import dev.vertique.resilience.ResolvedResiliencePolicy;
import dev.vertique.resilience.adapter.AdapterOperationIdentity;
import dev.vertique.resilience.adapter.ResilienceAdapterContext;
import dev.vertique.resilience.annotation.ResilienceAnnotations;
import dev.vertique.resilience.annotation.Resilient;
import io.vertx.core.Future;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Builds the shared resilience interceptor for methods carrying {@link Resilient}. */
@Singleton
final class ResilientAspect implements AspectProvider<Resilient> {

    private final Resilience resilience;
    private final ResiliencePolicyRegistry registry;
    private final ResilienceAdapterContext context;
    private final ConcurrentHashMap<AdapterOperationIdentity, ResiliencePipeline> pipelines = new ConcurrentHashMap<>();

    /** Creates the application-scoped aspect from Dagger-provided runtime dependencies. */
    @Inject
    ResilientAspect(Resilience resilience, Optional<ResiliencePolicyRegistry> registry) {
        this.resilience = Objects.requireNonNull(resilience, "resilience");
        this.registry = Objects.requireNonNull(registry, "registry").orElseGet(ResiliencePolicyRegistry::empty);
        this.context = resilience.adapterSupport().newContext();
    }

    /** Direct construction hook for isolated aspect tests. */
    ResilientAspect(Resilience resilience, ResiliencePolicyRegistry registry) {
        this(resilience, Optional.of(registry));
    }

    @Override
    public MethodInterceptor interceptor(MethodMetadata target, Resilient annotation) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(annotation, "annotation");
        AdapterOperationIdentity identity = operationIdentity(target);
        ResiliencePipeline pipeline = pipelines.computeIfAbsent(identity, ignored -> build(identity, target));
        return invocation -> pipeline.execute(invocation::proceed);
    }

    /** Closes all breaker and bulkhead state owned by this aspect. */
    Future<Void> close() {
        return context.close();
    }

    private ResiliencePipeline build(AdapterOperationIdentity identity, MethodMetadata target) {
        ResilienceAnnotations annotations = ResilienceAnnotations.resolve(target);
        ResiliencePolicyOverrides overrides = registry.layer(annotations, ResiliencePolicyOverrides.none());
        ResolvedResiliencePolicy policy =
                resilience.policyResolver().resolve(annotations, overrides, ResilienceDefaults.none());
        return context.pipeline(identity, policy);
    }

    private static AdapterOperationIdentity operationIdentity(MethodMetadata target) {
        List<String> components = new ArrayList<>(target.parameterTypes().length + 2);
        components.add(target.declaringType().getName());
        components.add(target.name());
        for (Class<?> parameterType : target.parameterTypes()) {
            components.add(parameterType.getName());
        }
        return new AdapterOperationIdentity("aop", components);
    }
}
