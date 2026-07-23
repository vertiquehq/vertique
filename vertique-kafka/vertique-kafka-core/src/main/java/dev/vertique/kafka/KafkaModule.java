// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dagger.multibindings.Multibinds;
import dev.vertique.context.ContextRuntimeModule;
import dev.vertique.context.DispatchEnvelopeBuilder;
import dev.vertique.context.DurableContextPropagator;
import dev.vertique.context.InboundExecutionContextScope;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.eventbus.EventBusClient;
import dev.vertique.core.health.HealthCheck;
import dev.vertique.core.health.HealthCheckModule;
import dev.vertique.core.health.Readiness;
import dev.vertique.deploy.DeployerModule;
import dev.vertique.deploy.VerticleDeployer;
import dev.vertique.kafka.config.KafkaConfig;
import dev.vertique.kafka.config.KafkaConsumerConfig;
import dev.vertique.kafka.config.KafkaProducerConfig;
import dev.vertique.kafka.health.KafkaConsumerHealthCheck;
import dev.vertique.kafka.interceptor.KafkaConsumerCaptureHook;
import dev.vertique.kafka.interceptor.KafkaConsumerInterceptor;
import dev.vertique.kafka.producer.KafkaProducerCaptureHook;
import dev.vertique.kafka.producer.KafkaProducerFactory;
import dev.vertique.kafka.serialization.KafkaSerdeProvider;
import dev.vertique.kafka.serialization.KafkaSerdeRegistry;
import dev.vertique.logging.LoggingContextModule;
import dev.vertique.services.DispatchModule;
import dev.vertique.services.ServiceContractRegistry;
import dev.vertique.services.ServiceRequestSender;
import dev.vertique.services.ServiceTargetResolver;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import java.util.Map;
import java.util.Set;

/**
 * Dagger module providing all Kafka consumer and producer infrastructure bindings.
 *
 * <p>Include this module in the application {@code @Component} to enable Kafka consumer
 * dispatch and typed producer proxies. This module transitively includes
 * {@link DispatchModule} (for {@link ServiceContractRegistry}), {@link DeployerModule},
 * and {@link HealthCheckModule}.
 *
 * <p>Extension points via multibinding:
 * <ul>
 *   <li>{@code Set<KafkaConsumerBinding<?>>} — contribute declarative consumer bindings (Model 2)</li>
 *   <li>{@code @KafkaConsumers Set<Object>} — contribute {@link KafkaListener @KafkaListener}
 *       routing interfaces (Model 3) or {@link KafkaRecordHandler} instances (Model 4)</li>
 *   <li>{@code Set<KafkaConsumerInterceptor>} — contribute consumer interceptors</li>
 *   <li>{@code Set<KafkaProducerCaptureHook>} — observe every producer send after it completes</li>
 * </ul>
 */
@Module(
        includes = {
            ContextRuntimeModule.class,
            LoggingContextModule.class,
            DispatchModule.class,
            DeployerModule.class,
            HealthCheckModule.class
        })
public abstract class KafkaModule {

    // --- Multibindings ---

    /**
     * Declares the empty-by-default multibinding for declarative consumer bindings.
     *
     * @return an empty set (applications contribute elements via {@code @IntoSet})
     */
    @Multibinds
    abstract Set<KafkaConsumerBinding<?>> kafkaConsumerBindings();

    /**
     * Declares the empty-by-default multibinding for consumer handler objects
     * ({@link KafkaListener @KafkaListener} interfaces and {@link KafkaRecordHandler} instances).
     *
     * @return an empty set (applications contribute elements via {@code @IntoSet})
     */
    @Multibinds
    @KafkaConsumers
    abstract Set<Object> kafkaConsumerHandlers();

    /**
     * Declares the empty-by-default multibinding for consumer pipeline interceptors.
     *
     * @return an empty set (applications contribute elements via {@code @IntoSet})
     */
    @Multibinds
    abstract Set<KafkaConsumerInterceptor> kafkaConsumerInterceptors();

    /**
     * Declares the (possibly empty) set of {@link KafkaConsumerCaptureHook} terminal-outcome
     * observers. Applications and extension modules contribute implementations via
     * {@code @IntoSet}. Observer-only — hooks never affect commit/retry/delivery.
     *
     * @return an empty set (elements contributed via {@code @IntoSet})
     */
    @Multibinds
    abstract Set<KafkaConsumerCaptureHook> kafkaConsumerCaptureHooks();

    /**
     * Declares the empty-by-default multibinding for producer send capture hooks.
     * Applications and extension modules contribute implementations via {@code @IntoSet}.
     * Observer-only — hooks never affect the send result.
     *
     * @return an empty set (elements contributed via {@code @IntoSet})
     */
    @Multibinds
    abstract Set<KafkaProducerCaptureHook> kafkaProducerCaptureHooks();

    /**
     * Declares the empty-by-default multibinding for value-format serde providers.
     * Format modules (e.g. {@code KafkaJsonModule}, {@code AvroModule}) contribute providers
     * via {@code @IntoSet}.
     *
     * @return an empty set (format modules contribute elements via {@code @IntoSet})
     */
    @Multibinds
    abstract Set<KafkaSerdeProvider> kafkaSerdeProviders();

    // --- Typed Config Boundary ---

    /**
     * Parses the {@code kafka} section of the root config into the typed {@link KafkaConfig} at the
     * Dagger provider boundary (config rule R2/R10). This is the <em>only</em> remaining
     * {@code @VertxConfig JsonObject} site in kafka-core: every runtime provider below depends on the
     * typed {@link KafkaConfig} (or the indexes derived from it), never on the raw root config.
     *
     * @param config the root application configuration
     * @param parser the injected config parser
     * @return the parsed, validated typed Kafka config
     */
    @Provides
    @Singleton
    static KafkaConfig kafkaConfig(@VertxConfig JsonObject config, ConfigParser parser) {
        return KafkaConfig.fromConfig(config, parser);
    }

    /**
     * Provides the immutable {@code name -> KafkaConsumerConfig} index built from the typed
     * {@link KafkaConfig} after validation.
     *
     * @param kafkaConfig the typed Kafka config
     * @return an immutable index of consumers keyed by name
     */
    @Provides
    @Singleton
    static Map<String, KafkaConsumerConfig> kafkaConsumerConfigIndex(KafkaConfig kafkaConfig) {
        return kafkaConfig.consumerIndex();
    }

    /**
     * Provides the immutable {@code name -> KafkaProducerConfig} index built from the typed
     * {@link KafkaConfig} after validation.
     *
     * @param kafkaConfig the typed Kafka config
     * @return an immutable index of producers keyed by name
     */
    @Provides
    @Singleton
    static Map<String, KafkaProducerConfig> kafkaProducerConfigIndex(KafkaConfig kafkaConfig) {
        return kafkaConfig.producerIndex();
    }

    // --- Singleton Providers ---

    /**
     * Provides the singleton {@link KafkaConsumerRegistry} built from all registered consumer sources.
     *
     * @param bindings declarative bindings from multibinding (Model 2)
     * @param handlers {@link KafkaListener @KafkaListener} classes and {@link KafkaRecordHandler}
     *     instances from multibinding (Models 3 and 4)
     * @param serviceRegistry the service contract registry for dispatch address resolution
     * @param serviceTargetResolver the resolver used as source of truth for stable target ids
     *     and runtime event bus addresses
     * @param serdeRegistry the value-format serde registry used to resolve formats and build deserializers
     * @param kafkaConfig the typed Kafka config
     * @return the built consumer registry
     */
    @Provides
    @Singleton
    static KafkaConsumerRegistry registry(
            Set<KafkaConsumerBinding<?>> bindings,
            @KafkaConsumers Set<Object> handlers,
            ServiceContractRegistry serviceRegistry,
            ServiceTargetResolver serviceTargetResolver,
            KafkaSerdeRegistry serdeRegistry,
            KafkaConfig kafkaConfig) {
        return KafkaConsumerRegistry.build(
                bindings, handlers, serviceRegistry, serviceTargetResolver, serdeRegistry, kafkaConfig);
    }

    /**
     * Provides the singleton {@link KafkaProducerFactory} for creating typed producer proxies
     * and raw send capability.
     *
     * @param vertx        the Vert.x instance used to create the underlying Kafka client
     * @param kafkaConfig  the typed Kafka config
     * @param propagator   the durable context propagator merged into outgoing record headers
     * @param serdeRegistry the value-format serde registry used to select per-method serializers
     * @param captureHooks the set of producer capture hooks; observer-only, sorted at construction time
     * @return the producer factory
     */
    @Provides
    @Singleton
    static KafkaProducerFactory producerFactory(
            Vertx vertx,
            KafkaConfig kafkaConfig,
            DurableContextPropagator propagator,
            KafkaSerdeRegistry serdeRegistry,
            Set<KafkaProducerCaptureHook> captureHooks) {
        return new KafkaProducerFactory(vertx, kafkaConfig, propagator, serdeRegistry, captureHooks);
    }

    /**
     * Provides the singleton {@link KafkaConsumerDeploymentManager} that deploys all consumer
     * verticles.
     *
     * @param vertx           the Vert.x instance for codec registration and verticle deployment
     * @param deployer        the verticle deployer for deployment lifecycle management
     * @param registry        the consumer registry providing all resolved consumer entries
     * @param interceptors    consumer pipeline interceptors from multibinding
     * @param producerFactory the shared Kafka producer factory for DLQ publishing
     * @param requestSender   the service request sender for target-aware dispatch
     * @param targetResolver  the resolver for looking up service targets by stable id
     * @param eventBusClient  the low-level event bus client for fire-and-forget sends
     * @param inboundExecutionContextScope the substrate lifecycle helper that binds inbound
     *        durable metadata and runs registered {@code InboundContextInitializer}s before
     *        dispatch
     * @param envelopeBuilder the dispatch envelope builder for constructing outgoing envelopes
     * @param serdeRegistry the value-format serde registry used for Avro router routing and route deserializers
     * @return the deployment manager
     */
    @Provides
    @Singleton
    static KafkaConsumerDeploymentManager deploymentManager(
            Vertx vertx,
            VerticleDeployer deployer,
            KafkaConsumerRegistry registry,
            Set<KafkaConsumerInterceptor> interceptors,
            Set<KafkaConsumerCaptureHook> captureHooks,
            KafkaProducerFactory producerFactory,
            ServiceRequestSender requestSender,
            ServiceTargetResolver targetResolver,
            EventBusClient eventBusClient,
            InboundExecutionContextScope inboundExecutionContextScope,
            DispatchEnvelopeBuilder envelopeBuilder,
            KafkaSerdeRegistry serdeRegistry) {
        return new KafkaConsumerDeploymentManager(
                vertx,
                deployer,
                registry,
                interceptors,
                captureHooks,
                producerFactory,
                requestSender,
                targetResolver,
                eventBusClient,
                inboundExecutionContextScope,
                envelopeBuilder,
                serdeRegistry);
    }

    /**
     * Contributes the Kafka consumer health check as a readiness indicator.
     *
     * @param check the Kafka consumer health check
     * @return the health check instance for readiness multibinding
     */
    @Provides
    @IntoSet
    @Readiness
    static HealthCheck kafkaHealthCheck(KafkaConsumerHealthCheck check) {
        return check;
    }
}
