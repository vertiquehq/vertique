// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import dev.vertique.context.DispatchEnvelopeBuilder;
import dev.vertique.context.InboundExecutionContextScope;
import dev.vertique.core.eventbus.EventBusClient;
import dev.vertique.core.eventbus.LocalMessageCodec;
import dev.vertique.core.extension.OrderedExtension;
import dev.vertique.core.lifecycle.LifecyclePhase;
import dev.vertique.deploy.VerticleDeployer;
import dev.vertique.deploy.VerticleDeployment;
import dev.vertique.kafka.config.KafkaSecretKeys;
import dev.vertique.kafka.interceptor.KafkaConsumerCaptureHook;
import dev.vertique.kafka.interceptor.KafkaConsumerInterceptor;
import dev.vertique.kafka.producer.KafkaProducerFactory;
import dev.vertique.services.ServiceRequestSender;
import dev.vertique.services.ServiceTargetResolver;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

/**
 * Deploys all Kafka consumer verticles from the {@link KafkaConsumerRegistry}.
 *
 * <p>Follows the same pattern as {@code ServiceDeploymentManager}:
 * <ul>
 *   <li>Registers local event bus codecs ({@code dispatch.envelope} and {@code dispatch.result})
 *       exactly once before deploying any verticles.</li>
 *   <li>Deploys one {@link KafkaConsumerVerticle} per enabled {@link ConsumerEntry} in parallel
 *       using {@link Future#join} semantics.</li>
 *   <li>On failure, rolls back all successfully deployed consumers.</li>
 * </ul>
 *
 * <p>Disabled consumers (where {@link ResolvedKafkaConsumerConfig#enabled()} is {@code false}) are
 * skipped entirely and not deployed.
 */
@Slf4j
public class KafkaConsumerDeploymentManager {

    // --- Dependencies ---

    private final Vertx vertx;
    private final VerticleDeployer deployer;
    private final KafkaConsumerRegistry registry;
    private final List<KafkaConsumerInterceptor> interceptors;
    private final Set<KafkaConsumerCaptureHook> captureHooks;
    private final KafkaProducerFactory producerFactory;
    private final ServiceRequestSender requestSender;
    private final ServiceTargetResolver targetResolver;
    private final EventBusClient eventBusClient;
    private final InboundExecutionContextScope inboundExecutionContextScope;
    private final DispatchEnvelopeBuilder envelopeBuilder;
    private final dev.vertique.kafka.serialization.KafkaSerdeRegistry serdeRegistry;

    // --- Runtime state ---

    private final AtomicBoolean codecsRegistered = new AtomicBoolean(false);
    private final Map<String, String> deploymentNames = new ConcurrentHashMap<>();

    /**
     * Creates a new deployment manager.
     *
     * @param vertx           the Vert.x instance used for deploying verticles and registering codecs
     * @param deployer        the verticle deployer for deployment lifecycle management
     * @param registry        the consumer registry providing all resolved consumer entries
     * @param interceptors    consumer interceptors contributed via Dagger multibinding
     * @param captureHooks    terminal-outcome capture hooks contributed via Dagger multibinding;
     *        passed through to each deployed {@link KafkaConsumerVerticle}
     * @param producerFactory the shared Kafka producer factory for DLQ publishing
     * @param requestSender   the service request sender for target-aware dispatch
     * @param targetResolver  the resolver for looking up service targets by stable id
     * @param eventBusClient  the low-level event bus client for fire-and-forget sends
     * @param inboundExecutionContextScope the substrate lifecycle helper that binds inbound
     *        durable metadata and runs registered {@code InboundContextInitializer}s before
     *        dispatch
     * @param envelopeBuilder the dispatch envelope builder for constructing outgoing envelopes
     * @param serdeRegistry   the value-format serde registry threaded into each verticle for Avro
     *        router routing and route deserializers
     */
    public KafkaConsumerDeploymentManager(
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
            dev.vertique.kafka.serialization.KafkaSerdeRegistry serdeRegistry) {
        this.vertx = vertx;
        this.deployer = deployer;
        this.registry = registry;
        this.interceptors =
                interceptors.stream().sorted(OrderedExtension.comparator()).collect(Collectors.toUnmodifiableList());
        this.captureHooks = captureHooks;
        this.producerFactory = producerFactory;
        this.requestSender = requestSender;
        this.targetResolver = targetResolver;
        this.eventBusClient = eventBusClient;
        this.inboundExecutionContextScope = inboundExecutionContextScope;
        this.envelopeBuilder = envelopeBuilder;
        this.serdeRegistry = serdeRegistry;
    }

    // --- Deployment ---

    /**
     * Deploys all enabled Kafka consumer verticles, registering local codecs first.
     *
     * <p>Codec registration is idempotent — duplicate registration exceptions are silently swallowed.
     * All enabled consumers are deployed in parallel ({@link Future#join}). If any deployment
     * fails, all successfully deployed consumers are rolled back and the returned future fails with
     * the original cause.
     *
     * @return a future that succeeds when all consumers are deployed, or fails if any deployment fails
     */
    public Future<Void> deployAll() {
        registerCodecs();
        List<ConsumerEntry> enabledEntries =
                registry.entries().stream().filter(e -> e.config().enabled()).collect(Collectors.toList());

        if (enabledEntries.isEmpty()) {
            log.info("No enabled Kafka consumers to deploy");
            return Future.succeededFuture();
        }

        List<Future<String>> deployments =
                enabledEntries.stream().map(this::deployConsumer).collect(Collectors.toList());

        return Future.join(deployments).<Void>mapEmpty().recover(cause -> rollback()
                .transform(v -> Future.failedFuture(cause)));
    }

    /**
     * Undeploys all currently tracked Kafka consumer verticles.
     *
     * <p>All undeploys are attempted regardless of individual failures ({@link Future#join}
     * semantics). Tracking entries are removed only on successful undeploy.
     *
     * @return a future that succeeds when all undeploy attempts have settled
     */
    public Future<Void> undeployAll() {
        return undeployEntries("Undeploy");
    }

    // --- Internal ---

    /**
     * Rolls back all currently tracked consumer deployments. Used internally when
     * {@link #deployAll()} fails to undo any successfully started consumers.
     *
     * @return a future that completes when all rollback undeploys have been attempted
     */
    private Future<Void> rollback() {
        return undeployEntries("Rollback");
    }

    /**
     * Undeploys all currently tracked entries. The {@code logContext} prefix is used in warning
     * messages to distinguish rollback from normal undeploy.
     *
     * @param logContext a short prefix for log messages (e.g., {@code "Undeploy"} or
     *     {@code "Rollback"})
     * @return a future that completes when all undeploy attempts have settled
     */
    private Future<Void> undeployEntries(String logContext) {
        List<Future<Void>> undeploys = new ArrayList<>();
        for (Map.Entry<String, String> entry : deploymentNames.entrySet()) {
            String name = entry.getValue();
            undeploys.add(deployer.undeploy(name)
                    .onSuccess(v -> deploymentNames.remove(entry.getKey()))
                    .onFailure(
                            cause -> log.warn("{}: failed to undeploy Kafka consumer: {}", logContext, name, cause)));
        }
        if (undeploys.isEmpty()) {
            return Future.succeededFuture();
        }
        return Future.join(undeploys).mapEmpty();
    }

    /**
     * Deploys a single consumer entry as a {@link KafkaConsumerVerticle}.
     *
     * @param entry the consumer entry to deploy
     * @return a future of the Vert.x deployment ID
     */
    private Future<String> deployConsumer(ConsumerEntry entry) {
        String deploymentName = consumerDeploymentName(entry);
        List<KafkaConsumerInterceptor> sortedInterceptors = this.interceptors;

        VerticleDeployment deployment = new VerticleDeployment(
                deploymentName,
                () -> new KafkaConsumerVerticle(
                        entry,
                        sortedInterceptors,
                        captureHooks,
                        producerFactory,
                        requestSender,
                        targetResolver,
                        eventBusClient,
                        inboundExecutionContextScope,
                        envelopeBuilder,
                        serdeRegistry),
                entry.config().deploymentOptions(),
                LifecyclePhase.SERVICES,
                10);

        return deployer.deploy(deployment)
                .onSuccess(id -> {
                    deploymentNames.put(entry.name(), deploymentName);
                    log.info(
                            "Deployed Kafka consumer: {} [{}] topic={} instances={}",
                            entry.name(),
                            entry.kind(),
                            entry.config().topic(),
                            entry.config().deploymentOptions().getInstances());
                    logConsumerConfig(entry);
                })
                .onFailure(cause -> log.error("Failed to deploy Kafka consumer: {}", entry.name(), cause));
    }

    /**
     * Builds a deterministic deployment name for a consumer entry.
     *
     * @param entry the consumer entry
     * @return the deployment name in format {@code kafka-consumer:{name}}
     */
    private static String consumerDeploymentName(ConsumerEntry entry) {
        return "kafka-consumer:" + entry.name();
    }

    /**
     * Registers the local event bus codecs for {@link dev.vertique.core.eventbus.DispatchEnvelope} and
     * {@link dev.vertique.core.eventbus.Result} if not already registered.
     *
     * <p>Each codec is registered independently. If registration fails for an unexpected reason,
     * the {@link AtomicBoolean} guard is reset so the next {@link #deployAll()} call will retry.
     */
    private void registerCodecs() {
        if (codecsRegistered.compareAndSet(false, true)) {
            boolean success = registerCodec("dispatch.envelope") & registerCodec("dispatch.result");
            if (success) {
                log.debug("Registered local codecs: dispatch.envelope, dispatch.result");
            } else {
                codecsRegistered.set(false);
                log.warn("Codec registration incomplete — will retry on next deployAll()");
            }
        }
    }

    /**
     * Registers a single local codec by name.
     *
     * @param name the codec name to register
     * @return {@code true} if registration succeeded (including already-registered), {@code false}
     *     on unexpected error
     */
    private boolean registerCodec(String name) {
        try {
            vertx.eventBus().registerCodec(new LocalMessageCodec<>(name));
            return true;
        } catch (IllegalStateException e) {
            log.debug("Local codec '{}' already registered", name);
            return true;
        } catch (Exception e) {
            log.error("Failed to register codec '{}': {}", name, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Logs the effective resolved Kafka properties for a consumer at DEBUG level, masking the values
     * of credential-bearing keys.
     *
     * <p>Masking is delegated to {@link KafkaSecretKeys#isSensitiveKey(String)} — the same centralized
     * union secret-key predicate the config records use for their {@code toString()} scrubbing — so a
     * key the config redaction would mask is never leaked here either. {@link KafkaSecretKeys#MASK} is
     * the rendered replacement.
     *
     * @param entry the consumer entry to log config for
     */
    private void logConsumerConfig(ConsumerEntry entry) {
        if (!log.isDebugEnabled()) {
            return;
        }
        log.debug("[{}] Effective Kafka properties:", entry.name());
        entry.config().kafkaProperties().forEach((key, value) -> {
            String displayValue = KafkaSecretKeys.isSensitiveKey(key) ? KafkaSecretKeys.MASK : value;
            log.debug("  {}={}", key, displayValue);
        });
    }
}
