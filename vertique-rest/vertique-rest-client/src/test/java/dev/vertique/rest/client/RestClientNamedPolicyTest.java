// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import dev.vertique.core.codegen.MethodMetadata;
import dev.vertique.core.codegen.ReflectiveMethodMetadata;
import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.resilience.Resilience;
import dev.vertique.resilience.ResiliencePolicyOverrides;
import dev.vertique.resilience.ResiliencePolicyRegistry;
import dev.vertique.resilience.RetryOverride;
import dev.vertique.resilience.TimeoutOverride;
import dev.vertique.resilience.annotation.CircuitBreaker;
import dev.vertique.resilience.annotation.ResilienceAnnotations;
import dev.vertique.resilience.annotation.Resilient;
import dev.vertique.resilience.annotation.Retry;
import dev.vertique.rest.client.config.RestClientConfig;
import dev.vertique.rest.client.config.RestClientRetryConfig;
import dev.vertique.rest.client.exception.RestClientResponseException;
import dev.vertique.rest.client.meta.ClientMethodMeta;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.client.WebClient;
import jakarta.ws.rs.GET;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** TP-001 proof for REST-client named-policy resolution and retry eligibility. */
class RestClientNamedPolicyTest {

    private static final String CLIENT_NAME = "payments";
    private static final long READ_TIMEOUT_MS = 1_000L;
    private static final dev.vertique.resilience.BackoffStrategy BACKOFF = retryCount -> 0L;

    private static Vertx vertx;

    @BeforeAll
    static void startVertx() {
        vertx = Vertx.vertx();
    }

    @AfterAll
    static void stopVertx() {
        if (vertx != null) {
            vertx.close();
        }
    }

    @RestClient(name = CLIENT_NAME, value = "http://payments")
    interface NamedRetryClient {

        @Resilient(policy = "payments")
        @Retry(maxRetries = 2)
        @GET
        Future<String> namedRetry();
    }

    @RestClient(name = CLIENT_NAME, value = "http://payments")
    interface InterfaceBreakerClient {

        @Resilient(policy = "payments")
        @CircuitBreaker
        @Retry(maxRetries = 2)
        String interfaceBreaker();
    }

    @RestClient(name = CLIENT_NAME, value = "http://payments")
    interface AnnotationOnlyRetryClient {

        @Retry(maxRetries = 2)
        String annotationOnlyRetry();
    }

    @RestClient(name = CLIENT_NAME, value = "http://payments")
    interface TimeoutOnlyClient {

        @Resilient(policy = "slow")
        @dev.vertique.resilience.annotation.Timeout(value = 500)
        String timeoutOnly();
    }

    @Test
    @DisplayName("layers client retry config over the named tier")
    void layersClientRetryConfigOverNamedTierAndFailsBuildOnUnknownPolicy() {
        RestClientRetryPolicy retryPolicy = new DefaultRestClientRetryPolicy();
        ResolvedPolicyFixture fixture = fixture(
                method(NamedRetryClient.class, "namedRetry"),
                registry(Map.of("payments", retry(5))),
                retryPolicy,
                null);

        assertThat(fixture.policy().retry()).isPresent();
        assertThat(fixture.policy().retry().orElseThrow().maxRetries()).isEqualTo(5);
    }

    @Test
    @DisplayName("client retry override wins over the named tier")
    void clientRetryOverrideWinsOverNamedTier() {
        RestClientRetryPolicy retryPolicy = new DefaultRestClientRetryPolicy();
        ResolvedPolicyFixture fixture = fixture(
                method(NamedRetryClient.class, "namedRetry"),
                registry(Map.of("payments", retry(5))),
                retryPolicy,
                new RestClientConfig(
                        CLIENT_NAME,
                        "http://payments",
                        null,
                        null,
                        null,
                        null,
                        new RestClientRetryConfig(9, null),
                        null));

        assertThat(fixture.policy().retry()).isPresent();
        assertThat(fixture.policy().retry().orElseThrow().maxRetries()).isEqualTo(9);
    }

    @Test
    @DisplayName("unknown named policy fails during eager pipeline construction")
    void unknownPolicyFailsDuringEagerPipelineFactoryConstruction() {
        MethodMetaFixture fixture = method(NamedRetryClient.class, "namedRetry");
        RestClientRetryPolicy retryPolicy = new DefaultRestClientRetryPolicy();
        Resilience resilience = Resilience.create(vertx);

        try {
            assertThatThrownBy(() -> new RestClientResiliencePipelineFactory(
                            resilience,
                            CLIENT_NAME,
                            NamedRetryClient.class,
                            READ_TIMEOUT_MS,
                            retryPolicy,
                            BACKOFF,
                            null,
                            null,
                            Map.of("namedRetry", fixture.meta()),
                            registry(Map.of())))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessage("resilience.policies.payments is not defined");
        } finally {
            resilience.close().toCompletionStage().toCompletableFuture().join();
        }
    }

    @Test
    @DisplayName("failed eager named-policy resolution closes builder-owned resources")
    void builderClosesResourcesWhenEagerNamedPolicyResolutionFails() {
        WebClient webClient = mock(WebClient.class);
        RestClientBuilder builder =
                new RestClientBuilder(vertx, registry(Map.of()), (ignored, options, pool) -> webClient);

        try {
            assertThatThrownBy(() -> builder.build(NamedRetryClient.class))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessage("resilience.policies.payments is not defined");
            Resilience ownedRuntime = builder.ownedResilience();
            assertThat(ownedRuntime)
                    .as("failed build must have created an owned runtime")
                    .isNotNull();
            assertThatThrownBy(() -> ownedRuntime.pipeline("T018-cleanup-probe"))
                    .as("failed build must close the owned runtime before returning the failure")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Resilience runtime is closed");
            verify(webClient).close();
        } finally {
            builder.close().toCompletionStage().toCompletableFuture().join();
        }
    }

    @Test
    @DisplayName("interface breaker rebuild carries the named policy")
    void interfaceBreakerRebuildCarriesNamedPolicy() {
        ResolvedPolicyFixture fixture = fixture(
                method(InterfaceBreakerClient.class, "interfaceBreaker"),
                registry(Map.of("payments", retry(5))),
                new DefaultRestClientRetryPolicy(),
                null);

        assertThat(fixture.withUsesInterfaceBreaker(true).policy().retry()).isPresent();
        assertThat(fixture.withUsesInterfaceBreaker(true)
                        .policy()
                        .retry()
                        .orElseThrow()
                        .maxRetries())
                .isEqualTo(5);
    }

    @Test
    @DisplayName("named-tier retry uses REST response eligibility")
    void namedTierRetryUsesRestEligibility() {
        RestClientRetryPolicy retryPolicy = new DefaultRestClientRetryPolicy();
        ResolvedPolicyFixture fixture = fixture(
                method(NamedRetryClient.class, "namedRetry"),
                registry(Map.of("payments", retry(3))),
                retryPolicy,
                null);

        assertThat(fixture.policy().retry()).isPresent();
        assertThat(fixture.policy().retry().orElseThrow().fallbackPolicy().shouldRetry(response(404), 0))
                .isFalse();
    }

    @Test
    @DisplayName("annotation-only retry uses REST response eligibility")
    void annotationOnlyRetryUsesRestEligibility() {
        RestClientRetryPolicy retryPolicy = new DefaultRestClientRetryPolicy();
        ResolvedPolicyFixture fixture = fixture(
                method(AnnotationOnlyRetryClient.class, "annotationOnlyRetry"), registry(Map.of()), retryPolicy, null);

        assertThat(fixture.policy().retry()).isPresent();
        assertThat(fixture.policy().retry().orElseThrow().fallbackPolicy().shouldRetry(response(404), 0))
                .isFalse();
        assertThat(fixture.policy().retry().orElseThrow().fallbackPolicy().shouldRetry(response(503), 0))
                .isTrue();
    }

    @Test
    @DisplayName("timeout-only named tier does not activate retry or fallback")
    void timeoutOnlyTierDoesNotActivateRetryOrFallback() {
        RestClientRetryPolicy retryPolicy = new DefaultRestClientRetryPolicy();
        ResolvedPolicyFixture fixture = fixture(
                method(TimeoutOnlyClient.class, "timeoutOnly"),
                registry(Map.of("slow", timeout(900))),
                retryPolicy,
                null);

        assertThat(fixture.policy().retry()).isEmpty();
    }

    private static ResolvedPolicyFixture fixture(
            MethodMetaFixture method,
            ResiliencePolicyRegistry registry,
            RestClientRetryPolicy retryPolicy,
            RestClientConfig clientConfig) {
        return new ResolvedPolicyFixture(
                new RestClientResilienceConfigAdapter(
                        Resilience.create(vertx).policyResolver(),
                        READ_TIMEOUT_MS,
                        retryPolicy,
                        BACKOFF,
                        clientConfig,
                        null,
                        registry),
                method.meta(),
                false);
    }

    private record ResolvedPolicyFixture(
            RestClientResilienceConfigAdapter adapter, ClientMethodMeta meta, boolean usesInterfaceBreaker) {

        private dev.vertique.resilience.ResolvedResiliencePolicy policy() {
            return adapter.resolve(meta, usesInterfaceBreaker);
        }

        private ResolvedPolicyFixture withUsesInterfaceBreaker(boolean value) {
            return new ResolvedPolicyFixture(adapter, meta, value);
        }
    }

    private record MethodMetaFixture(ClientMethodMeta meta) {}

    private static MethodMetaFixture method(Class<?> type, String name) {
        try {
            return method(type.getDeclaredMethod(name));
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("missing test fixture method " + type.getName() + "#" + name, failure);
        }
    }

    private static MethodMetaFixture method(Method method) {
        MethodMetadata metadata = new ReflectiveMethodMetadata(method, List.of());
        return new MethodMetaFixture(new ClientMethodMeta(
                metadata,
                "GET",
                "/",
                List.of(),
                String.class,
                String.class,
                false,
                false,
                false,
                "application/json",
                "application/json",
                null,
                ResilienceAnnotations.resolve(method),
                false));
    }

    private static ResiliencePolicyRegistry registry(Map<String, ResiliencePolicyOverrides> policies) {
        return name -> Optional.ofNullable(policies.get(name))
                .orElseThrow(() -> new ConfigurationException("resilience.policies." + name + " is not defined"));
    }

    private static ResiliencePolicyOverrides retry(int maxRetries) {
        return new ResiliencePolicyOverrides(
                Optional.empty(),
                Optional.of(new RetryOverride(
                        Optional.empty(),
                        OptionalInt.of(maxRetries),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty())),
                Optional.empty(),
                Optional.empty());
    }

    private static ResiliencePolicyOverrides timeout(long timeoutMs) {
        return new ResiliencePolicyOverrides(
                Optional.of(new TimeoutOverride(Optional.empty(), OptionalLong.of(timeoutMs))),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private static RestClientResponseException response(int statusCode) {
        return new RestClientResponseException(
                statusCode, "synthetic", Buffer.buffer(), MultiMap.caseInsensitiveMultiMap());
    }
}
