// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services.resilience;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.deploy.SupervisionConfig;
import dev.vertique.resilience.Resilience;
import dev.vertique.resilience.ResiliencePipeline;
import dev.vertique.resilience.ResiliencePolicyOverrides;
import dev.vertique.resilience.ResiliencePolicyRegistry;
import dev.vertique.resilience.ResolvedResiliencePolicy;
import dev.vertique.resilience.RetryBackoff;
import dev.vertique.resilience.annotation.ResilienceAnnotations;
import dev.vertique.resilience.annotation.Resilient;
import dev.vertique.resilience.annotation.Retry;
import dev.vertique.resilience.config.ResiliencePolicyConfig;
import dev.vertique.resilience.config.RetryPolicyConfig;
import dev.vertique.services.ServiceContractRegistry;
import dev.vertique.services.ServiceRegistrationViolation;
import dev.vertique.services.config.RetryOverride;
import dev.vertique.services.config.ServiceConfig;
import dev.vertique.services.config.ServiceOperationConfig;
import dev.vertique.services.config.ServicesConfig;
import dev.vertique.services.dispatch.ServiceMethodDescriptor;
import dev.vertique.services.dispatch.ServiceMethodMeta;
import dev.vertique.services.exception.ServiceRegistrationException;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/** Verifies Services named-policy layering, validation, compatibility, and execution. */
@ExtendWith(VertxExtension.class)
@Timeout(20)
class ServiceResilienceNamedPolicyTest {

    interface NamedPolicyService {
        @Resilient(policy = "payments")
        @Retry(maxRetries = 2)
        void declared();

        @Resilient(policy = "payments")
        @Retry(maxRetries = 2)
        void overridden();

        @Resilient(policy = "payments")
        void anchorOnly();

        @Resilient(policy = "missing")
        void unknown();

        @Resilient(policy = "partial")
        void partial();

        @Resilient(policy = "partial")
        void partiallyOverridden();

        @Resilient(policy = "payments")
        @Retry(maxRetries = 2)
        void registryConstructor();

        @Resilient(policy = "payments")
        Future<String> retryOnly();
    }

    @Test
    @DisplayName("TP-001: layers operation overrides over named tiers and rejects unknown policies")
    void layersOperationOverrideOverNamedTierAndRejectsUnknownPolicies(Vertx vertx) throws Exception {
        Resilience resilience = Resilience.create(vertx);
        try {
            ResiliencePolicyRegistry registry = name -> switch (name) {
                case "payments" ->
                    new ResiliencePolicyConfig("payments", null, new RetryPolicyConfig(5, null, null, null), null, null)
                            .toOverrides();
                case "partial" ->
                    new ResiliencePolicyOverrides(
                            Optional.empty(),
                            Optional.of(new RetryPolicyConfig(5, null, null, null).toOverrides()),
                            Optional.empty(),
                            Optional.empty());
                case "missing" -> throw new ConfigurationException("resilience.policies.missing is not defined");
                default -> throw new ConfigurationException("resilience.policies." + name + " is not defined");
            };
            ServiceResilienceConfigAdapter adapter = adapter(
                    resilience,
                    registry,
                    Map.of(
                            "overridden",
                                    new ServiceOperationConfig(
                                            "overridden", null, null, null, new RetryOverride(9, null, null, null)),
                            "partiallyOverridden",
                                    new ServiceOperationConfig(
                                            "partiallyOverridden",
                                            null,
                                            null,
                                            null,
                                            new RetryOverride(null, 100L, null, null))));

            ResolvedResiliencePolicy declared = adapter.resolve(meta("declared"));
            assertEquals(5, declared.retry().orElseThrow().maxRetries());

            ResolvedResiliencePolicy overridden = adapter.resolve(meta("overridden"));
            assertEquals(9, overridden.retry().orElseThrow().maxRetries());

            ResolvedResiliencePolicy anchorOnly = adapter.resolve(meta("anchorOnly"));
            assertNotNull(anchorOnly.retry().orElse(null));

            ServiceRegistrationException failure =
                    assertThrows(ServiceRegistrationException.class, () -> adapter.validate(List.of(entry("unknown"))));
            assertEquals(1, failure.violations().size());
            ServiceRegistrationViolation violation = failure.violations().getFirst();
            assertEquals("resilience policy configuration is invalid", violation.message());
            ConfigurationException cause = assertInstanceOf(ConfigurationException.class, failure.getCause());
            assertEquals("resilience.policies.missing is not defined", cause.getMessage());

            ResolvedResiliencePolicy partial = adapter.resolve(meta("partial"));
            assertEquals(5, partial.retry().orElseThrow().maxRetries());
            RetryBackoff.Exponential partialBackoff = assertInstanceOf(
                    RetryBackoff.Exponential.class,
                    partial.retry().orElseThrow().backoff());
            assertEquals(500L, partialBackoff.initialDelayMs());
            assertEquals(2.0d, partialBackoff.multiplier());
            assertEquals(30_000L, partialBackoff.maxDelayMs());

            ResolvedResiliencePolicy partiallyOverridden = adapter.resolve(meta("partiallyOverridden"));
            assertEquals(5, partiallyOverridden.retry().orElseThrow().maxRetries());
            RetryBackoff.Exponential partialOverrideBackoff = assertInstanceOf(
                    RetryBackoff.Exponential.class,
                    partiallyOverridden.retry().orElseThrow().backoff());
            assertEquals(100L, partialOverrideBackoff.initialDelayMs());
            assertEquals(2.0d, partialOverrideBackoff.multiplier());
            assertEquals(30_000L, partialOverrideBackoff.maxDelayMs());

            ServiceResilienceConfigAdapter registryAdapter = new ServiceResilienceConfigAdapter(
                    resilience, new ServicesConfig(null, List.of()), Map.of(), Optional.of(registry));
            ResolvedResiliencePolicy registryPolicy = registryAdapter.resolve(meta("registryConstructor"));
            assertEquals(5, registryPolicy.retry().orElseThrow().maxRetries());
            RetryBackoff.Exponential registryBackoff = assertInstanceOf(
                    RetryBackoff.Exponential.class,
                    registryPolicy.retry().orElseThrow().backoff());
            assertEquals(500L, registryBackoff.initialDelayMs());
            assertEquals(2.0d, registryBackoff.multiplier());
            assertEquals(30_000L, registryBackoff.maxDelayMs());
        } finally {
            resilience.close().toCompletionStage().toCompletableFuture().join();
        }
    }

    @Test
    @DisplayName("TP-002: activates a named tier and retries once on a transient failure")
    void activatesNamedTierAndRetriesOnceOnATransientFailure(Vertx vertx) throws Exception {
        Resilience resilience = Resilience.create(vertx);
        ServiceResiliencePipelineFactory factory = null;
        try {
            ResiliencePolicyRegistry registry = ResiliencePolicyRegistry.of(List.of(
                    new ResiliencePolicyConfig("payments", null, new RetryPolicyConfig(2, 1L, null, 1L), null, null)));
            ServiceResilienceConfigAdapter adapter = adapter(resilience, registry, Map.of());
            factory = new ServiceResiliencePipelineFactory(adapter, resilience);

            ResiliencePipeline pipeline = factory.pipeline(meta("retryOnly"));
            assertNotNull(pipeline);
            AtomicInteger attempts = new AtomicInteger();
            String result = pipeline.execute(() -> attempts.incrementAndGet() == 1
                            ? Future.failedFuture(new IllegalStateException("sentinel-attempt"))
                            : Future.succeededFuture("ok"))
                    .toCompletionStage()
                    .toCompletableFuture()
                    .join();

            assertEquals("ok", result);
            assertEquals(2, attempts.get());
        } finally {
            if (factory != null) {
                factory.close().toCompletionStage().toCompletableFuture().join();
            }
            resilience.close().toCompletionStage().toCompletableFuture().join();
        }
    }

    private static ServiceResilienceConfigAdapter adapter(
            Resilience resilience, ResiliencePolicyRegistry registry, Map<String, ServiceOperationConfig> operations) {
        ServiceConfig service = new ServiceConfig(
                "test",
                "named-policy",
                1,
                false,
                null,
                SupervisionConfig.DEFAULT,
                operations.entrySet().stream()
                        .map(entry -> new ServiceOperationConfig(
                                entry.getKey(),
                                entry.getValue().sendTimeoutMs(),
                                entry.getValue().timeout(),
                                entry.getValue().circuitBreaker(),
                                entry.getValue().retry()))
                        .toList());
        return new ServiceResilienceConfigAdapter(
                resilience,
                new ServicesConfig(null, List.of(service)),
                Map.of(new ServicesConfig.ServiceKey("test", "named-policy"), service),
                Optional.of(registry));
    }

    private static ServiceMethodMeta meta(String operation) throws Exception {
        Method method = NamedPolicyService.class.getMethod(operation);
        return ServiceMethodMeta.ofDirect(
                new Object(),
                ServiceMethodDescriptor.of(method),
                "services/test/named-policy/" + operation,
                "test.named-policy." + operation,
                "test",
                "named-policy",
                operation,
                null,
                method.getReturnType() == Future.class ? String.class : Void.class,
                List.of(),
                ResilienceAnnotations.resolve(NamedPolicyService.class, method),
                List.of(),
                List.of(),
                false);
    }

    private static ServiceContractRegistry.ContractEntry<NamedPolicyService> entry(String operation) throws Exception {
        ServiceMethodMeta meta = meta(operation);
        return new ServiceContractRegistry.ContractEntry<>(
                NamedPolicyService.class,
                new Object(),
                "test",
                "named-policy",
                "services/test/named-policy",
                "test.named-policy",
                Map.of(operation, meta),
                new DeploymentOptions());
    }
}
