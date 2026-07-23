// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import dev.vertique.kafka.config.KafkaConfig;
import dev.vertique.kafka.serialization.KafkaSerdeRegistry;
import dev.vertique.services.ServiceContractRegistry;
import dev.vertique.services.ServiceTargetResolver;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * Facade that builds and holds the resolved {@link ConsumerEntry} list for all
 * Kafka consumer bindings.
 *
 * <p>Create instances via
 * {@link #build(Set, Set, ServiceContractRegistry, ServiceTargetResolver, KafkaSerdeRegistry, KafkaConfig)}.
 * The constructor is private; all validation and scanning is delegated to
 * {@link KafkaConsumerRegistrar}.
 */
@Slf4j
public class KafkaConsumerRegistry {

    private final List<ConsumerEntry> entries;

    private KafkaConsumerRegistry(List<ConsumerEntry> entries) {
        this.entries = entries;
    }

    // --- Factory ---

    /**
     * Builds the registry by scanning all four consumption model sources.
     *
     * <p>Delegates scanning and validation to {@link KafkaConsumerRegistrar}. If any
     * violations are found, a {@link KafkaRegistrationException} is thrown with the full
     * list of violations.
     *
     * @param bindings declarative bindings from Dagger multibinding (Model 2)
     * @param handlers {@link KafkaListener} classes and {@link KafkaRecordHandler} instances
     *     from Dagger multibinding (Models 3 and 4)
     * @param serviceRegistry the service contract registry for dispatch address resolution
     * @param serviceTargetResolver the resolver used as source of truth for stable target ids
     *     and runtime event bus addresses
     * @param serdeRegistry the serde registry used to resolve value formats and build deserializers
     * @param kafkaConfig the typed {@code kafka} config (supplies the per-consumer config index and the
     *     global format / schema-registry / connection property bags)
     * @return a fully built registry ready for use by the deployment manager
     * @throws KafkaRegistrationException if any validation violations are found
     */
    public static KafkaConsumerRegistry build(
            Set<KafkaConsumerBinding<?>> bindings,
            Set<Object> handlers,
            ServiceContractRegistry serviceRegistry,
            ServiceTargetResolver serviceTargetResolver,
            KafkaSerdeRegistry serdeRegistry,
            KafkaConfig kafkaConfig) {
        KafkaConsumerRegistrar registrar = new KafkaConsumerRegistrar();
        List<ConsumerEntry> entries =
                registrar.scan(bindings, handlers, serviceRegistry, serviceTargetResolver, serdeRegistry, kafkaConfig);
        KafkaConsumerRegistry registry = new KafkaConsumerRegistry(entries);
        registry.logDiagnostics();
        return registry;
    }

    // --- Query ---

    /**
     * Returns all resolved consumer entries.
     *
     * @return an unmodifiable list of all consumer entries
     */
    public List<ConsumerEntry> entries() {
        return entries;
    }

    /**
     * Returns the total number of registered consumers (enabled and disabled).
     *
     * @return total consumer count
     */
    public int totalCount() {
        return entries.size();
    }

    /**
     * Returns the number of enabled consumers.
     *
     * @return enabled consumer count
     */
    public long enabledCount() {
        return entries.stream().filter(e -> e.config().enabled()).count();
    }

    // --- Diagnostics ---

    /**
     * Logs a structured summary of all registered consumers at INFO level.
     */
    private void logDiagnostics() {
        log.info("Kafka consumer registry built: {} consumer(s)", entries.size());
        for (ConsumerEntry entry : entries) {
            log.info(
                    "  {} [{}] topic={} group={} instances={}",
                    entry.name(),
                    entry.kind(),
                    entry.config().topic(),
                    entry.config().groupId(),
                    entry.config().deploymentOptions().getInstances());
        }
    }
}
