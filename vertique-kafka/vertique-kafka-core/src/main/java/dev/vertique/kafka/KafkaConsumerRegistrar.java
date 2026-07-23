// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import dev.vertique.kafka.config.KafkaConfig;
import dev.vertique.kafka.serialization.KafkaSerdeRegistry;
import dev.vertique.services.ServiceContractRegistry;
import dev.vertique.services.ServiceTargetResolver;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * Scans all four Kafka consumption models and produces {@link ConsumerEntry} objects.
 *
 * <p>Called once at startup by {@link KafkaConsumerRegistry#build}. All validation violations
 * are collected before failing, enabling complete error reporting in a single pass.
 *
 * <p>Scanned sources (in order):
 * <ol>
 *   <li><strong>Model 1</strong> — {@code @KafkaSource} annotations on service implementation methods
 *       discovered via {@link ServiceContractRegistry}.</li>
 *   <li><strong>Model 2</strong> — Declarative {@link KafkaConsumerBinding} instances contributed
 *       via Dagger multibinding.</li>
 *   <li><strong>Model 3</strong> — {@code Class<?>} objects with {@link KafkaListener} contributed
 *       via the {@link KafkaConsumers} multibinding, defining routing rules with
 *       {@link KafkaHandler}-annotated methods.</li>
 *   <li><strong>Model 4</strong> — {@link KafkaRecordHandler} instances with {@link KafkaListener}
 *       contributed via the same multibinding.</li>
 * </ol>
 *
 * <p>Cross-source validations:
 * <ul>
 *   <li>No duplicate binding names across all sources.</li>
 *   <li>{@link ErrorStrategy#RETRY} requires {@link CommitStrategy#MANUAL}.</li>
 *   <li>{@link ErrorStrategy#DEAD_LETTER} requires {@link CommitStrategy#MANUAL}.</li>
 *   <li>Topic must not be blank after config resolution.</li>
 *   <li>Group ID must not be blank after config resolution.</li>
 * </ul>
 *
 * <p>Scanning logic is delegated to {@link KafkaConsumerScanner}.
 */
@Slf4j
class KafkaConsumerRegistrar {

    // --- Public API ---

    /**
     * Scans all four consumption model sources and returns the resolved consumer entries.
     *
     * @param bindings declarative bindings from Dagger multibinding (Model 2)
     * @param handlers {@link KafkaListener} classes and {@link KafkaRecordHandler} instances
     *     from Dagger multibinding (Models 3 and 4)
     * @param serviceRegistry the service contract registry for address resolution and validation
     * @param serviceTargetResolver the resolver used to derive stable target ids and runtime
     *     addresses for operations registered with {@link dev.vertique.services.ServiceOperation}
     * @param serdeRegistry the serde registry used to resolve value formats and build deserializers
     * @param kafkaConfig the typed {@code kafka} config (supplies the per-consumer config index and
     *     the global format / schema-registry / connection property bags)
     * @return an unmodifiable list of all resolved consumer entries
     * @throws KafkaRegistrationException if any validation violations are found
     */
    List<ConsumerEntry> scan(
            Set<KafkaConsumerBinding<?>> bindings,
            Set<Object> handlers,
            ServiceContractRegistry serviceRegistry,
            ServiceTargetResolver serviceTargetResolver,
            KafkaSerdeRegistry serdeRegistry,
            KafkaConfig kafkaConfig) {

        List<String> violations = new ArrayList<>();
        List<ConsumerEntry> entries = new ArrayList<>();
        Set<String> usedNames = new HashSet<>();

        KafkaConsumerScanner scanner =
                new KafkaConsumerScanner(serviceTargetResolver, serdeRegistry, kafkaConfig.consumerIndex());

        // 1. Scan @KafkaSource from service registry (Model 1)
        scanner.scanKafkaSources(serviceRegistry, kafkaConfig, entries, usedNames, violations);

        // 2. Process declarative bindings (Model 2)
        scanner.processBindings(bindings, serviceRegistry, kafkaConfig, entries, usedNames, violations);

        // 3. Scan @KafkaListener contributions (Models 3 and 4)
        scanner.scanListeners(handlers, serviceRegistry, kafkaConfig, entries, usedNames, violations);

        if (!violations.isEmpty()) {
            // The registry is being aborted — close any framework-built deserializers already created
            // for the valid entries so their registry clients do not leak. Model-2 custom deserializers
            // are user-owned and left untouched.
            closeFrameworkOwnedDeserializers(entries);
            throw new KafkaRegistrationException(violations);
        }
        return List.copyOf(entries);
    }

    private static void closeFrameworkOwnedDeserializers(List<ConsumerEntry> entries) {
        for (ConsumerEntry entry : entries) {
            if (entry.frameworkOwnedDeserializer() && entry.deserializer() != null) {
                try {
                    entry.deserializer().close();
                } catch (RuntimeException closeFailure) {
                    log.warn(
                            "[{}] failed to close the aborted consumer's framework-owned deserializer;"
                                    + " a serde resource may have leaked",
                            entry.name(),
                            closeFailure);
                }
            }
        }
    }
}
