// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client;

import dev.vertique.resilience.CircuitBreaker;
import dev.vertique.resilience.Resilience;
import dev.vertique.resilience.ResiliencePipeline;
import dev.vertique.resilience.ResolvedResiliencePolicy;
import dev.vertique.resilience.adapter.AdapterOperationIdentity;
import dev.vertique.resilience.adapter.ResilienceAdapterContext;
import dev.vertique.rest.client.config.RestClientConfig;
import dev.vertique.rest.client.meta.ClientMethodMeta;
import io.vertx.circuitbreaker.CircuitBreakerOptions;
import io.vertx.core.Future;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Builds one common-runtime pipeline per method for one REST client instance. */
final class RestClientResiliencePipelineFactory {

    private final String clientName;
    private final Class<?> clientInterface;
    private final RestClientResilienceConfigAdapter configAdapter;
    private final ResilienceAdapterContext context;
    private final CircuitBreaker interfaceBreaker;
    private final Map<ClientMethodMeta, ResiliencePipeline> pipelines = new ConcurrentHashMap<>();

    RestClientResiliencePipelineFactory(
            Resilience resilience,
            String clientName,
            Class<?> clientInterface,
            long readTimeoutMs,
            RestClientRetryPolicy retryPolicy,
            dev.vertique.resilience.BackoffStrategy backoffStrategy,
            RestClientConfig clientConfig,
            CircuitBreakerOptions interfaceCircuitBreakerOptions,
            Map<?, ClientMethodMeta> methodMetas) {
        this.clientName = clientName;
        this.clientInterface = clientInterface;
        this.context = resilience.adapterSupport().newContext();
        this.configAdapter = new RestClientResilienceConfigAdapter(
                resilience.policyResolver(),
                readTimeoutMs,
                retryPolicy,
                backoffStrategy,
                clientConfig,
                interfaceCircuitBreakerOptions);
        dev.vertique.resilience.CircuitBreaker shared = null;
        var config = configAdapter.interfaceCircuitBreakerConfig();
        if (config != null) {
            shared = context.circuitBreaker(
                    new AdapterOperationIdentity(
                            "rest-client.interface-circuit", List.of(clientName, clientInterface.getName())),
                    config);
        }
        this.interfaceBreaker = shared;
        methodMetas.values().forEach(this::pipeline);
    }

    /** Returns the prebuilt pipeline for a method. */
    ResiliencePipeline pipeline(ClientMethodMeta meta) {
        return pipelines.computeIfAbsent(meta, this::build);
    }

    /** Closes this client instance's breaker context and fences its active executions. */
    Future<Void> close() {
        return context.close();
    }

    private ResiliencePipeline build(ClientMethodMeta meta) {
        boolean usesInterfaceBreaker = interfaceBreaker != null
                && !meta.methodMetadata().hasAnnotation(dev.vertique.resilience.annotation.CircuitBreaker.class);
        ResolvedResiliencePolicy policy = configAdapter.resolve(meta, usesInterfaceBreaker);
        AdapterOperationIdentity identity = new AdapterOperationIdentity(
                "rest-client.method",
                java.util.stream.Stream.concat(
                                java.util.stream.Stream.of(
                                        clientName,
                                        meta.methodMetadata().declaringType().getName(),
                                        meta.methodMetadata().name()),
                                java.util.Arrays.stream(meta.methodMetadata().parameterTypes())
                                        .map(Class::getName))
                        .toList());
        if (usesInterfaceBreaker) {
            return context.pipeline(identity, policy, interfaceBreaker, RestClientFailureClassifier::countsAsFailure);
        }
        if (policy.circuitBreaker().isPresent()) {
            return context.pipeline(identity, policy, RestClientFailureClassifier::countsAsFailure);
        }
        return context.pipeline(identity, policy);
    }

    /** HTTP classification is intentionally private to the REST adapter. */
    private static final class RestClientFailureClassifier {
        private static boolean countsAsFailure(Throwable failure) {
            if (!(failure instanceof dev.vertique.rest.client.exception.RestClientResponseException response)) {
                return true;
            }
            int status = response.statusCode();
            return status < 400 || status >= 500;
        }
    }
}
