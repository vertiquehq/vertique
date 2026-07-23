// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.context.ContextScopeBinder;
import dev.vertique.context.DefaultContextHolder;
import dev.vertique.context.DispatchEnvelopeBuilder;
import dev.vertique.context.DurableContextMetadataRegistry;
import dev.vertique.context.DurableContextPropagator;
import dev.vertique.context.InboundDispatchScope;
import dev.vertique.context.InboundExecutionContextScope;
import dev.vertique.context.ServiceDispatchContextCapturer;
import dev.vertique.context.ServiceDispatchContextRegistry;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.eventbus.EventBusClient;
import dev.vertique.core.eventbus.EventBusExceptionMapper;
import dev.vertique.kafka.serialization.KafkaSerdeRegistry;
import dev.vertique.kafka.serialization.TestJsonSerdeProvider;
import dev.vertique.services.ServiceRequestSender;
import dev.vertique.services.ServiceSupervisor;
import dev.vertique.services.ServiceTargetResolver;
import dev.vertique.services.config.ServicesConfig;
import dev.vertique.services.policy.PolicyChainBuilder;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import java.util.Set;

/**
 * Shared test support for Kafka integration tests. Builds the {@link ServiceRequestSender},
 * {@link ServiceTargetResolver}, and {@link EventBusClient} dependency chain from a Vert.x instance.
 */
final class KafkaTestSupport {

    private KafkaTestSupport() {}

    // --- Helpers ---

    /**
     * Returns a lenient {@link ConfigParser} suitable for test config parsing. Uses
     * {@link DefaultConfigParser} backed by {@link DefaultConfigMapper#lenient()} so that
     * string-coercion and unknown-property tolerance are active, matching the production
     * boundary parser behavior.
     *
     * @return a lenient config parser for test use
     */
    static ConfigParser configParser() {
        return new DefaultConfigParser(DefaultConfigMapper.lenient());
    }

    /**
     * Creates an {@link EventBusClient} backed by the given Vert.x instance.
     *
     * @param vertx the Vert.x instance
     * @return a new event bus client
     */
    static EventBusClient eventBusClient(Vertx vertx) {
        return new EventBusClient(vertx, new EventBusExceptionMapper());
    }

    /**
     * Creates a {@link ServiceRequestSender} with an always-available supervisor mock.
     *
     * @param vertx the Vert.x instance
     * @return a new service request sender
     */
    static ServiceRequestSender requestSender(Vertx vertx) {
        ServiceSupervisor supervisor = mock(ServiceSupervisor.class);
        when(supervisor.isAvailable(any())).thenReturn(true);
        ServicesConfig servicesConfig = ServicesConfig.fromConfig(new JsonObject(), configParser());
        return new ServiceRequestSender(eventBusClient(vertx), supervisor, servicesConfig, servicesConfig.index());
    }

    /**
     * Creates a {@link PolicyChainBuilder} whose per-service config index is derived from
     * the given root Vert.x config object. The {@code services} section is parsed via
     * {@link ServicesConfig#fromConfig(JsonObject)} so the builder receives a properly typed index
     * rather than the raw {@link JsonObject}.
     *
     * @param vertx  the Vert.x instance
     * @param config the root Vert.x config object
     * @return a new policy chain builder
     */
    static PolicyChainBuilder policyChainBuilder(Vertx vertx, JsonObject config) {
        return new PolicyChainBuilder(
                vertx, ServicesConfig.fromConfig(config, configParser()).index());
    }

    /**
     * Returns a no-op mock {@link ServiceTargetResolver}. Use this only for tests where dispatch
     * does not go through the stableTargetId path. For ITs that need real target resolution,
     * pass the actual {@code ServiceTargetResolver.of(registry)} instead.
     *
     * @return a mock target resolver
     */
    static ServiceTargetResolver noOpTargetResolver() {
        return mock(ServiceTargetResolver.class);
    }

    /**
     * Returns a no-op {@link DurableContextPropagator} backed by empty SPI registries and the
     * shared {@link DefaultContextHolder}. Suitable for tests that do not exercise durable
     * propagation but need to satisfy the constructor dependency.
     *
     * @return a no-op propagator
     */
    static DurableContextPropagator noOpPropagator() {
        DefaultContextHolder holder = new DefaultContextHolder();
        return new DurableContextPropagator(
                new DurableContextMetadataRegistry(Set.of(), Set.of()), holder, new ContextScopeBinder(holder));
    }

    /**
     * Returns a JSON-only {@link KafkaSerdeRegistry} backed by {@link TestJsonSerdeProvider},
     * suitable for tests that do not exercise alternative value formats.
     *
     * <p>{@link TestJsonSerdeProvider} is a self-contained test fixture that mirrors
     * {@code JsonSerdeProvider} from {@code vertique-kafka-json}; it is used here to avoid a
     * Maven reactor cycle ({@code json → core} main, {@code core → json} test).
     *
     * @return a JSON-capable serde registry
     */
    static KafkaSerdeRegistry jsonSerdeRegistry() {
        return new KafkaSerdeRegistry(Set.of(new TestJsonSerdeProvider()));
    }

    /**
     * Returns a no-op {@link InboundExecutionContextScope} backed by empty SPI registries, no
     * initializers, and a fresh {@link DefaultContextHolder}. Suitable for Kafka tests that do
     * not exercise correlation seeding or durable propagation but need to satisfy the verticle /
     * dispatcher constructor dependency.
     *
     * @return a no-op inbound execution context scope
     */
    static InboundExecutionContextScope noOpInboundExecutionContextScope() {
        DefaultContextHolder holder = new DefaultContextHolder();
        return new InboundExecutionContextScope(
                new InboundDispatchScope(),
                new DurableContextPropagator(
                        new DurableContextMetadataRegistry(Set.of(), Set.of()), holder, new ContextScopeBinder(holder)),
                Set.of());
    }

    /**
     * Returns a no-op {@link DispatchEnvelopeBuilder} backed by empty SPI registries and no MDC
     * capture. Suitable for tests that do not exercise service-dispatch context propagation.
     *
     * @return a no-op envelope builder
     */
    static DispatchEnvelopeBuilder noOpEnvelopeBuilder() {
        DefaultContextHolder holder = new DefaultContextHolder();
        ServiceDispatchContextCapturer capturer =
                new ServiceDispatchContextCapturer(new ServiceDispatchContextRegistry(Set.of(), Set.of()), holder);
        return new DispatchEnvelopeBuilder(capturer);
    }
}
