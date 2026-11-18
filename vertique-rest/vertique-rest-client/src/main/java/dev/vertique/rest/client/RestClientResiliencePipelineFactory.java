// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client;

import dev.vertique.resilience.CircuitBreaker;
import dev.vertique.resilience.Resilience;
import dev.vertique.resilience.ResiliencePipeline;
import dev.vertique.resilience.ResiliencePolicyRegistry;
import dev.vertique.resilience.ResolvedResiliencePolicy;
import dev.vertique.resilience.adapter.AdapterOperationIdentity;
import dev.vertique.resilience.adapter.ResilienceAdapterContext;
import dev.vertique.rest.client.config.RestClientConfig;
import dev.vertique.rest.client.meta.ClientMethodMeta;
import io.vertx.circuitbreaker.CircuitBreakerOptions;
import io.vertx.core.Future;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;

/** Builds one common-runtime pipeline per method for one REST client instance. */
final class RestClientResiliencePipelineFactory {

    private final String clientName;
    private final Class<?> clientInterface;
    private final RestClientResilienceConfigAdapter configAdapter;
    private final ResilienceAdapterContext context;
    private final CircuitBreaker interfaceBreaker;
    private volatile boolean closed;
    private final Map<ClientMethodMeta, ResiliencePipeline> pipelines = new ConcurrentHashMap<>();
    private final Map<ClientMethodMeta, OptionalLong> timeouts = new ConcurrentHashMap<>();

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
        this(
                resilience,
                clientName,
                clientInterface,
                readTimeoutMs,
                retryPolicy,
                backoffStrategy,
                clientConfig,
                interfaceCircuitBreakerOptions,
                methodMetas,
                ResiliencePolicyRegistry.empty());
    }

    RestClientResiliencePipelineFactory(
            Resilience resilience,
            String clientName,
            Class<?> clientInterface,
            long readTimeoutMs,
            RestClientRetryPolicy retryPolicy,
            dev.vertique.resilience.BackoffStrategy backoffStrategy,
            RestClientConfig clientConfig,
            CircuitBreakerOptions interfaceCircuitBreakerOptions,
            Map<?, ClientMethodMeta> methodMetas,
            ResiliencePolicyRegistry registry) {
        this.clientName = clientName;
        this.clientInterface = clientInterface;
        ResilienceAdapterContext context = resilience.adapterSupport().newContext();
        this.context = context;
        try {
            this.configAdapter = new RestClientResilienceConfigAdapter(
                    resilience.policyResolver(),
                    readTimeoutMs,
                    retryPolicy,
                    backoffStrategy,
                    clientConfig,
                    interfaceCircuitBreakerOptions,
                    registry);
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
        } catch (RuntimeException | Error failure) {
            context.close();
            throw failure;
        }
    }

    /** Returns the prebuilt pipeline for a method. */
    ResiliencePipeline pipeline(ClientMethodMeta meta) {
        return pipelines.computeIfAbsent(meta, this::build);
    }

    /** Returns the method's resolved timeout in milliseconds; empty when the timeout is disabled. */
    OptionalLong timeoutMs(ClientMethodMeta meta) {
        pipeline(meta);
        return timeouts.get(meta);
    }

    /** Closes this client instance's breaker context and fences its active executions. */
    Future<Void> close() {
        closed = true;
        return context.close();
    }

    /** Whether {@link #close()} has been called; the pipelines then refuse every execution. */
    boolean isClosed() {
        return closed;
    }

    private ResiliencePipeline build(ClientMethodMeta meta) {
        boolean usesInterfaceBreaker = interfaceBreaker != null
                && !meta.methodMetadata().hasAnnotation(dev.vertique.resilience.annotation.CircuitBreaker.class);
        ResolvedResiliencePolicy policy = configAdapter.resolve(meta, usesInterfaceBreaker);
        timeouts.put(
                meta,
                policy.timeout()
                        .map(timeout -> OptionalLong.of(timeout.timeoutMs()))
                        .orElseGet(OptionalLong::empty));
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
