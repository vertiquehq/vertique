// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.eventbus.DispatchEnvelope;
import dev.vertique.core.eventbus.EventBusAddressUnavailableException;
import dev.vertique.core.eventbus.EventBusClient;
import dev.vertique.core.eventbus.EventBusDispatchException;
import dev.vertique.core.eventbus.EventBusTimeoutException;
import dev.vertique.core.eventbus.Result;
import dev.vertique.core.resilience.CircuitBreaker;
import dev.vertique.core.resilience.ResilienceAnnotations;
import dev.vertique.core.resilience.Retry;
import dev.vertique.core.resilience.Timeout;
import dev.vertique.services.config.ServiceConfig;
import dev.vertique.services.config.ServicesConfig;
import dev.vertique.services.dispatch.ServiceMethodDescriptor;
import dev.vertique.services.dispatch.ServiceMethodMeta;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link ServiceRequestSender}.
 *
 * <p>Covers supervisor availability gating, the full send-timeout computation logic including
 * annotation-driven, config-driven, and combined precedence cases, all new overloads, and error
 * enrichment from event bus transport exceptions to service-specific exceptions.
 */
@ExtendWith(MockitoExtension.class)
class ServiceRequestSenderTest {

    // --- Annotated fixture interfaces used to obtain annotation instances ---

    interface WithTimeout {
        @Timeout(5000)
        void op();
    }

    interface WithCBTimeoutMs {
        @CircuitBreaker(maxFailures = 3, timeoutMs = 2000, resetTimeoutMs = 10000)
        void op();
    }

    interface WithCBNoTimeoutMs {
        @CircuitBreaker(maxFailures = 3)
        void op();
    }

    interface WithRetry {
        @Retry(maxRetries = 2, delayMs = 100, backoffMultiplier = 2.0, maxDelayMs = 30000)
        void op();
    }

    interface WithTimeoutAndRetry {
        @Timeout(1000)
        @Retry(maxRetries = 2, delayMs = 200, backoffMultiplier = 1.0, maxDelayMs = 30000)
        void op();
    }

    interface NoAnnotations {
        void op();
    }

    // --- Test contract for ResolvedServiceTarget ---

    interface SomeContract {}

    // --- Mocks and system under test ---

    @Mock
    private EventBusClient eventBusClient;

    @Mock
    private ServiceSupervisor supervisor;

    // --- Helpers ---

    private static ResilienceAnnotations resolveMeta(Class<?> iface) throws Exception {
        Method method = iface.getMethod("op");
        return ResilienceAnnotations.resolve(iface, method);
    }

    private static ServiceMethodMeta makeMeta(String namespace, String name, String operation, ResilienceAnnotations ra)
            throws Exception {
        Method method = NoAnnotations.class.getMethod("op");
        return ServiceMethodMeta.ofDirect(
                new Object(),
                ServiceMethodDescriptor.of(method),
                "services/" + namespace + "/" + name + "/" + operation,
                null,
                namespace,
                name,
                operation,
                null,
                Void.class,
                List.of(),
                ra,
                List.of(),
                List.of(),
                false);
    }

    private static ServiceMethodMeta makeMeta(ResilienceAnnotations ra) throws Exception {
        return makeMeta("svc-type", "my-svc", "my-op", ra);
    }

    private ResolvedServiceTarget makeTarget(ServiceMethodMeta meta) {
        return new ResolvedServiceTarget("t.svc.op", SomeContract.class, "t", "svc", "op", meta, "services/t/svc/op");
    }

    /**
     * Creates a lenient {@link ConfigParser} instance for test-side config parsing.
     *
     * @return a {@link DefaultConfigParser} backed by a lenient {@link DefaultConfigMapper}
     */
    private static ConfigParser configParser() {
        return new DefaultConfigParser(DefaultConfigMapper.lenient());
    }

    /**
     * Builds a {@link ServiceRequestSender} wired with the typed services config parsed from the given
     * root config object — exercising the same boundary parser the Dagger provider uses, so the test
     * JSON proves end-to-end parse-and-resolve parity.
     *
     * @param rootConfig the root application config (may contain a {@code services} section)
     * @return a sender backed by the parsed typed config and index
     */
    private ServiceRequestSender senderFor(JsonObject rootConfig) {
        ServicesConfig servicesConfig = ServicesConfig.fromConfig(rootConfig, configParser());
        Map<ServicesConfig.ServiceKey, ServiceConfig> index = servicesConfig.index();
        return new ServiceRequestSender(eventBusClient, supervisor, servicesConfig, index);
    }

    // --- Supervisor gating ---

    @Nested
    @DisplayName("Supervisor availability check")
    class SupervisorGating {

        @Test
        @DisplayName("Should return failed future with ServiceUnavailableException when supervisor denies")
        void supervisorUnavailableReturnsFailed() throws Exception {
            when(supervisor.isAvailable(SomeContract.class)).thenReturn(false);

            ServiceMethodMeta meta = makeMeta(ResilienceAnnotations.NONE);
            ResolvedServiceTarget target = makeTarget(meta);

            ServiceRequestSender sender = senderFor(new JsonObject());
            Future<Result<?>> result = sender.send(target, null);

            assertInstanceOf(
                    ServiceUnavailableException.class, result.cause(), "cause should be ServiceUnavailableException");

            // eventBusClient must never be called
            verify(eventBusClient, never()).request(anyString(), any(), anyLong());
        }

        @Test
        @DisplayName("Should attempt event bus send when supervisor reports available")
        void supervisorAvailableAttemptsSend() throws Exception {
            when(supervisor.isAvailable(SomeContract.class)).thenReturn(true);
            when(eventBusClient.request(anyString(), any(), anyLong())).thenReturn(Future.failedFuture("no handler"));

            ServiceMethodMeta meta = makeMeta(ResilienceAnnotations.NONE);
            ResolvedServiceTarget target = makeTarget(meta);

            ServiceRequestSender sender = senderFor(new JsonObject());
            sender.send(target, null);

            verify(eventBusClient).request(eq("services/t/svc/op"), any(), anyLong());
        }
    }

    // --- send(target, body, explicitTimeout) ---

    @Nested
    @DisplayName("send(ResolvedServiceTarget, DispatchEnvelope, long)")
    class SendWithExplicitTimeout {

        @Test
        @DisplayName("Supervisor unavailable → fails with ServiceUnavailableException")
        void supervisorUnavailable_fails() throws Exception {
            when(supervisor.isAvailable(SomeContract.class)).thenReturn(false);

            ServiceMethodMeta meta = makeMeta(ResilienceAnnotations.NONE);
            ResolvedServiceTarget target = makeTarget(meta);

            ServiceRequestSender sender = senderFor(new JsonObject());
            Future<Result<?>> result = sender.send(target, null, 5_000L);

            assertInstanceOf(ServiceUnavailableException.class, result.cause());
            verify(eventBusClient, never()).request(anyString(), any(), anyLong());
        }

        @Test
        @DisplayName("Supervisor available → delegates to eventBusClient with explicit timeout")
        void supervisorAvailable_delegatesWithExplicitTimeout() throws Exception {
            when(supervisor.isAvailable(SomeContract.class)).thenReturn(true);
            Result<?> successResult = Result.success("ok");
            when(eventBusClient.request(anyString(), any(), anyLong()))
                    .thenReturn(Future.succeededFuture(successResult));

            ServiceMethodMeta meta = makeMeta(ResilienceAnnotations.NONE);
            ResolvedServiceTarget target = makeTarget(meta);

            ServiceRequestSender sender = senderFor(new JsonObject());
            Future<Result<?>> result = sender.send(target, null, 7_500L);

            assertTrue(result.succeeded());
            verify(eventBusClient).request(eq("services/t/svc/op"), any(), eq(7_500L));
        }

        @Test
        @DisplayName("EventBusTimeoutException → enriched as ServiceTimeoutException with contract")
        void timeoutException_enrichedWithContract() throws Exception {
            when(supervisor.isAvailable(SomeContract.class)).thenReturn(true);
            EventBusTimeoutException transportException =
                    new EventBusTimeoutException("services/t/svc/op", new RuntimeException("timeout"));
            when(eventBusClient.request(anyString(), any(), anyLong()))
                    .thenReturn(Future.failedFuture(transportException));

            ServiceMethodMeta meta = makeMeta(ResilienceAnnotations.NONE);
            ResolvedServiceTarget target = makeTarget(meta);

            ServiceRequestSender sender = senderFor(new JsonObject());
            Future<Result<?>> result = sender.send(target, null, 5_000L);

            ServiceTimeoutException ste = assertInstanceOf(ServiceTimeoutException.class, result.cause());
            assertSame(SomeContract.class, ste.contract());
        }
    }

    // --- send(address, body, timeout) — no supervisor ---

    @Nested
    @DisplayName("send(String, DispatchEnvelope, long)")
    class SendAddressBased {

        @Test
        @DisplayName("Delegates directly to eventBusClient, no supervisor check")
        void delegatesToEventBusClientNoSupervisor() throws Exception {
            Result<?> successResult = Result.success("ok");
            when(eventBusClient.request(eq("services/some/address"), any(), eq(3_000L)))
                    .thenReturn(Future.succeededFuture(successResult));

            ServiceRequestSender sender = senderFor(new JsonObject());
            Future<Result<?>> result = sender.send("services/some/address", null, 3_000L);

            assertTrue(result.succeeded());
            verify(supervisor, never()).isAvailable(any());
            verify(eventBusClient).request(eq("services/some/address"), any(), eq(3_000L));
        }

        @Test
        @DisplayName("Returns whatever eventBusClient returns without enrichment on success")
        void returnsClientResultOnSuccess() throws Exception {
            Result<?> successResult = Result.success("ok");
            when(eventBusClient.request(anyString(), any(), anyLong()))
                    .thenReturn(Future.succeededFuture(successResult));

            ServiceRequestSender sender = senderFor(new JsonObject());
            Future<Result<?>> result = sender.send("services/addr", null, 1_000L);

            assertTrue(result.succeeded());
            assertSame(successResult, result.result());
        }
    }

    // --- sendOneWay(target, body) ---

    @Nested
    @DisplayName("sendOneWay(ResolvedServiceTarget, DispatchEnvelope)")
    class SendOneWay {

        @Test
        @DisplayName("Supervisor available → calls eventBusClient.send() and returns succeeded future")
        void supervisorAvailable_callsSend() throws Exception {
            when(supervisor.isAvailable(SomeContract.class)).thenReturn(true);

            ServiceMethodMeta meta = makeMeta(ResilienceAnnotations.NONE);
            ResolvedServiceTarget target = makeTarget(meta);
            DispatchEnvelope<?> body = mock(DispatchEnvelope.class);

            ServiceRequestSender sender = senderFor(new JsonObject());
            Future<Void> result = sender.sendOneWay(target, body);

            assertTrue(result.succeeded());
            verify(eventBusClient).send(eq("services/t/svc/op"), eq(body));
        }

        @Test
        @DisplayName("Supervisor unavailable → fails with ServiceUnavailableException, does not call send()")
        void supervisorUnavailable_failsWithoutSend() throws Exception {
            when(supervisor.isAvailable(SomeContract.class)).thenReturn(false);

            ServiceMethodMeta meta = makeMeta(ResilienceAnnotations.NONE);
            ResolvedServiceTarget target = makeTarget(meta);

            ServiceRequestSender sender = senderFor(new JsonObject());
            Future<Void> result = sender.sendOneWay(target, null);

            assertInstanceOf(ServiceUnavailableException.class, result.cause());
            verify(eventBusClient, never()).send(anyString(), any());
        }
    }

    // --- Error enrichment ---

    @Nested
    @DisplayName("Error enrichment")
    class ErrorEnrichment {

        private ServiceRequestSender sender;

        @BeforeEach
        void setUp() {
            when(supervisor.isAvailable(SomeContract.class)).thenReturn(true);
            sender = senderFor(new JsonObject());
        }

        @Test
        @DisplayName("EventBusTimeoutException → ServiceTimeoutException with contract")
        void timeoutException_wrappedWithContract() throws Exception {
            EventBusTimeoutException cause =
                    new EventBusTimeoutException("services/t/svc/op", new RuntimeException("timeout"));
            when(eventBusClient.request(anyString(), any(), anyLong())).thenReturn(Future.failedFuture(cause));

            ServiceMethodMeta meta = makeMeta(ResilienceAnnotations.NONE);
            ResolvedServiceTarget target = makeTarget(meta);

            Future<Result<?>> result = sender.send(target, null);

            ServiceTimeoutException ste = assertInstanceOf(ServiceTimeoutException.class, result.cause());
            assertSame(SomeContract.class, ste.contract());
            assertEquals("services/t/svc/op", ste.address());
        }

        @Test
        @DisplayName("EventBusAddressUnavailableException → ServiceUnavailableException with contract")
        void addressUnavailableException_wrappedWithContract() throws Exception {
            EventBusAddressUnavailableException cause =
                    new EventBusAddressUnavailableException("services/t/svc/op", new RuntimeException("no handlers"));
            when(eventBusClient.request(anyString(), any(), anyLong())).thenReturn(Future.failedFuture(cause));

            ServiceMethodMeta meta = makeMeta(ResilienceAnnotations.NONE);
            ResolvedServiceTarget target = makeTarget(meta);

            Future<Result<?>> result = sender.send(target, null);

            ServiceUnavailableException sue = assertInstanceOf(ServiceUnavailableException.class, result.cause());
            assertSame(SomeContract.class, sue.contract());
        }

        @Test
        @DisplayName("EventBusDispatchException → ServiceDispatchException with contract")
        void dispatchException_wrappedWithContract() throws Exception {
            EventBusDispatchException cause = new EventBusDispatchException(
                    "services/t/svc/op", "recipient failure", new RuntimeException("err"));
            when(eventBusClient.request(anyString(), any(), anyLong())).thenReturn(Future.failedFuture(cause));

            ServiceMethodMeta meta = makeMeta(ResilienceAnnotations.NONE);
            ResolvedServiceTarget target = makeTarget(meta);

            Future<Result<?>> result = sender.send(target, null);

            ServiceDispatchException sde = assertInstanceOf(ServiceDispatchException.class, result.cause());
            assertSame(SomeContract.class, sde.contract());
            assertEquals("services/t/svc/op", sde.address());
        }

        @Test
        @DisplayName("Non-EventBus exception passes through unchanged")
        void nonEventBusException_passesThrough() throws Exception {
            RuntimeException original = new RuntimeException("some other error");
            when(eventBusClient.request(anyString(), any(), anyLong())).thenReturn(Future.failedFuture(original));

            ServiceMethodMeta meta = makeMeta(ResilienceAnnotations.NONE);
            ResolvedServiceTarget target = makeTarget(meta);

            Future<Result<?>> result = sender.send(target, null);

            assertSame(original, result.cause());
        }
    }

    // --- computeSendTimeout ---

    @Nested
    @DisplayName("computeSendTimeout()")
    class ComputeSendTimeout {

        private ServiceRequestSender sender;

        @BeforeEach
        void setUp() {
            sender = senderFor(new JsonObject());
        }

        @Test
        @DisplayName("No annotations → default 30000 ms")
        void noAnnotations_returnsDefault() throws Exception {
            ServiceMethodMeta meta = makeMeta(ResilienceAnnotations.NONE);

            long timeout = sender.computeSendTimeout(meta);

            assertEquals(30_000L, timeout);
        }

        @Test
        @DisplayName("@Timeout(5000) only → 5000 * 1 + 0 backoff + 1000 buffer = 6000")
        void timeoutOnly_returnsTimeoutPlusBuffer() throws Exception {
            ServiceMethodMeta meta = makeMeta(resolveMeta(WithTimeout.class));

            long timeout = sender.computeSendTimeout(meta);

            assertEquals(6_000L, timeout);
        }

        @Test
        @DisplayName("@CircuitBreaker(timeoutMs=2000) only → 2000 * 1 + 0 backoff + 1000 buffer = 3000")
        void circuitBreakerTimeoutMs_usedWhenNoTimeout() throws Exception {
            ServiceMethodMeta meta = makeMeta(resolveMeta(WithCBTimeoutMs.class));

            long timeout = sender.computeSendTimeout(meta);

            assertEquals(3_000L, timeout);
        }

        @Test
        @DisplayName("@CircuitBreaker without timeoutMs → falls back to default 30000")
        void circuitBreakerNoTimeoutMs_returnsDefault() throws Exception {
            ServiceMethodMeta meta = makeMeta(resolveMeta(WithCBNoTimeoutMs.class));

            long timeout = sender.computeSendTimeout(meta);

            // CB with timeoutMs=-1 → perAttemptMs stays DEFAULT (30000); no retries → 30000 + 1000
            assertEquals(31_000L, timeout);
        }

        @Test
        @DisplayName("@Retry(maxRetries=2, delayMs=100, multiplier=2.0, maxDelay=30000) → correct backoff sum")
        void retryOnly_computesBackoff() throws Exception {
            // attempt 0: delay = min(100 * 2^0, 30000) = 100, jitter = min(100, 1000) = 100 → 200
            // attempt 1: delay = min(100 * 2^1, 30000) = 200, jitter = min(200, 1000) = 200 → 400
            // totalBackoff = 600
            // perAttemptMs = 30000 (DEFAULT — no @Timeout or @CB.timeoutMs)
            // result = 30000 * (1+2) + 600 + 1000 = 91600
            ServiceMethodMeta meta = makeMeta(resolveMeta(WithRetry.class));

            long timeout = sender.computeSendTimeout(meta);

            assertEquals(91_600L, timeout);
        }

        @Test
        @DisplayName("@Timeout(1000) + @Retry(maxRetries=2, delayMs=200, multiplier=1.0) → combined computation")
        void timeoutAndRetry_combinesCorrectly() throws Exception {
            // perAttemptMs = 1000 (@Timeout wins)
            // attempt 0: delay = min(200 * 1^0, 30000) = 200, jitter = min(200, 1000) = 200 → 400
            // attempt 1: delay = min(200 * 1^1, 30000) = 200, jitter = min(200, 1000) = 200 → 400
            // totalBackoff = 800
            // result = 1000 * (1+2) + 800 + 1000 = 4800
            ServiceMethodMeta meta = makeMeta(resolveMeta(WithTimeoutAndRetry.class));

            long timeout = sender.computeSendTimeout(meta);

            assertEquals(4_800L, timeout);
        }

        @Test
        @DisplayName("Per-operation config sendTimeoutMs overrides resilience computation")
        void operationConfigOverridesResilience() throws Exception {
            JsonObject config = rootConfig(contracts(new JsonObject()
                    .put(
                            "svc-type",
                            new JsonObject()
                                    .put(
                                            "my-svc",
                                            operations(new JsonObject()
                                                    .put("my-op", new JsonObject().put("sendTimeoutMs", 10_000L)))))));

            ServiceRequestSender configuredSender = senderFor(config);
            ServiceMethodMeta meta = makeMeta(resolveMeta(WithTimeout.class));

            long timeout = configuredSender.computeSendTimeout(meta);

            assertEquals(10_000L, timeout);
        }

        @Test
        @DisplayName("Per-service config sendTimeoutMs overrides global config and resilience")
        void serviceConfigOverridesGlobal() throws Exception {
            JsonObject config = rootConfig(contracts(new JsonObject()
                    .put("svc-type", new JsonObject().put("my-svc", new JsonObject().put("sendTimeoutMs", 8_000L)))));

            ServiceRequestSender configuredSender = senderFor(config);
            ServiceMethodMeta meta = makeMeta(ResilienceAnnotations.NONE);

            long timeout = configuredSender.computeSendTimeout(meta);

            assertEquals(8_000L, timeout);
        }

        @Test
        @DisplayName("Global services.sendTimeoutMs overrides resilience computation")
        void globalConfigOverridesResilience() throws Exception {
            JsonObject config = rootConfig(new JsonObject().put("sendTimeoutMs", 5_000L));

            ServiceRequestSender configuredSender = senderFor(config);
            ServiceMethodMeta meta = makeMeta(resolveMeta(WithTimeout.class));

            long timeout = configuredSender.computeSendTimeout(meta);

            assertEquals(5_000L, timeout);
        }

        @Test
        @DisplayName("Explicit config timeout shorter than resilience timeout still returns the explicit value")
        void explicitTimeoutShorterThanResilience_returnsExplicit() throws Exception {
            // @Retry with 2 retries would compute a large resilience timeout; config overrides to 1000
            JsonObject config = rootConfig(new JsonObject().put("sendTimeoutMs", 1_000L));

            ServiceRequestSender configuredSender = senderFor(config);
            ServiceMethodMeta meta = makeMeta(resolveMeta(WithRetry.class));

            long timeout = configuredSender.computeSendTimeout(meta);

            // Explicit 1000 < resilience (91600) → still returns 1000, just logs WARN
            assertEquals(1_000L, timeout);
        }
    }

    // --- Cascade parity (op > service > global > resilience > 30000) ---

    @Nested
    @DisplayName("Send-timeout cascade parity")
    class CascadeParity {

        @Test
        @DisplayName("op-level sendTimeoutMs beats service-level beats global")
        void cascade_opBeatsServiceBeatsGlobal() throws Exception {
            // global=5000, service=8000, op=10000 — op wins
            JsonObject config = rootConfig(new JsonObject()
                    .put("sendTimeoutMs", 5_000L)
                    .mergeIn(contracts(new JsonObject()
                            .put(
                                    "svc-type",
                                    new JsonObject()
                                            .put(
                                                    "my-svc",
                                                    new JsonObject()
                                                            .put("sendTimeoutMs", 8_000L)
                                                            .mergeIn(operations(new JsonObject()
                                                                    .put(
                                                                            "my-op",
                                                                            new JsonObject()
                                                                                    .put(
                                                                                            "sendTimeoutMs",
                                                                                            10_000L)))))))));

            ServiceMethodMeta meta = makeMeta(ResilienceAnnotations.NONE);
            assertEquals(10_000L, senderFor(config).computeSendTimeout(meta), "op-level wins");

            // Drop the op override → service-level (8000) wins
            JsonObject serviceOnly = rootConfig(new JsonObject()
                    .put("sendTimeoutMs", 5_000L)
                    .mergeIn(contracts(new JsonObject()
                            .put(
                                    "svc-type",
                                    new JsonObject().put("my-svc", new JsonObject().put("sendTimeoutMs", 8_000L))))));
            assertEquals(8_000L, senderFor(serviceOnly).computeSendTimeout(meta), "service-level wins over global");

            // Drop the service override → global (5000) wins
            JsonObject globalOnly = rootConfig(new JsonObject().put("sendTimeoutMs", 5_000L));
            assertEquals(5_000L, senderFor(globalOnly).computeSendTimeout(meta), "global wins");
        }

        @Test
        @DisplayName("an explicit sendTimeoutMs at any level beats the resilience-computed value")
        void explicitBeatsResilience() throws Exception {
            // @Retry would compute 91600ms; an explicit 1000 at the global level wins
            JsonObject config = rootConfig(new JsonObject().put("sendTimeoutMs", 1_000L));
            ServiceMethodMeta meta = makeMeta(resolveMeta(WithRetry.class));

            assertEquals(1_000L, senderFor(config).computeSendTimeout(meta));
        }

        @Test
        @DisplayName("no explicit at any level and no resilience annotations → 30000")
        void absentAll_fallsTo30s() throws Exception {
            ServiceMethodMeta meta = makeMeta(ResilienceAnnotations.NONE);

            assertEquals(30_000L, senderFor(new JsonObject()).computeSendTimeout(meta));
        }

        @Test
        @DisplayName("a service configured under the empty default namespace (the _ key) resolves cleanly")
        void emptyType_resolves() throws Exception {
            // The empty default namespace is addressed by the reserved _ key under contracts; a
            // per-service override there is keyed under (namespace="", name) and resolves for a
            // blank-namespace meta.
            ServiceMethodMeta meta = makeMeta("", "my-svc", "my-op", ResilienceAnnotations.NONE);

            JsonObject configured = rootConfig(contracts(new JsonObject()
                    .put("_", new JsonObject().put("my-svc", new JsonObject().put("sendTimeoutMs", 7_000L)))));
            assertEquals(
                    7_000L,
                    senderFor(configured).computeSendTimeout(meta),
                    "per-service override under the _ empty-namespace key resolves");

            // Global scalar still applies (it is not keyed by namespace), so it wins over resilience/default.
            JsonObject globalOnly = rootConfig(new JsonObject().put("sendTimeoutMs", 5_000L));
            assertEquals(5_000L, senderFor(globalOnly).computeSendTimeout(meta));

            // With no config at all, a blank-namespace service resolves cleanly to the 30000 default.
            assertEquals(30_000L, senderFor(new JsonObject()).computeSendTimeout(meta));
        }
    }

    // --- Config-shape helpers ---

    /**
     * Wraps a {@code services} section JSON in a root config object.
     *
     * @param servicesSection the {@code services} section
     * @return the root config with the section under {@code services}
     */
    private static JsonObject rootConfig(JsonObject servicesSection) {
        return new JsonObject().put("services", servicesSection);
    }

    /**
     * Wraps a per-namespace service-group map under the {@code contracts} key, matching the external
     * shape {@code services.contracts.{namespace}.{name}}. Any sibling scalars on the {@code services}
     * section (e.g. the global {@code sendTimeoutMs}) are merged in alongside the {@code contracts}
     * object.
     *
     * @param namespaceGroups the per-namespace groups keyed by namespace ({@code _} for the empty
     *     default namespace)
     * @return a JSON object {@code {"contracts": namespaceGroups}}
     */
    private static JsonObject contracts(JsonObject namespaceGroups) {
        return new JsonObject().put("contracts", namespaceGroups);
    }

    /**
     * Wraps a keyed-operation map under the {@code operations} key, matching the external shape
     * {@code services.contracts.{namespace}.{name}.operations.{operation}}.
     *
     * @param operationsMap the keyed-operation map
     * @return a JSON object {@code {"operations": operationsMap}}
     */
    private static JsonObject operations(JsonObject operationsMap) {
        return new JsonObject().put("operations", operationsMap);
    }
}
