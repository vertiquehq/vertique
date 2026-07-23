// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import dev.vertique.kafka.serialization.KafkaDeserializer;
import jakarta.annotation.Nullable;
import java.util.List;

/**
 * Unified consumer entry metadata produced by {@link KafkaConsumerRegistrar} for all
 * consumption models. Used internally by {@link KafkaConsumerDeploymentManager}.
 *
 * <p>The {@link Kind} determines which fields are populated:
 * <ul>
 *   <li>{@link Kind#BINDING} — {@code valueType}, {@code targetAddress}, {@code targetOneWay},
 *       and optionally {@code deserializer} and {@code filter} are set; {@code routes} is empty.</li>
 *   <li>{@link Kind#ROUTER} — {@code routes} is non-empty; {@code valueType} and
 *       {@code targetAddress} are {@code null} (targets live in each {@link RouteEntry}).</li>
 *   <li>{@link Kind#HANDLER} — {@code handler} and {@code deserializer} are non-null;
 *       {@code routes} is empty and {@code targetAddress} is {@code null}.</li>
 * </ul>
 *
 * @param name binding name used as the config reference key and deployment identifier
 * @param config resolved runtime configuration for this consumer
 * @param kind the consumption model kind
 * @param valueType the deserialization target type; {@code null} for {@link Kind#ROUTER}
 * @param targetAddress event bus address to dispatch records to; {@code null} for
 *     {@link Kind#ROUTER} and {@link Kind#HANDLER}
 * @param stableTargetId durable dot-delimited stable target id for the resolved operation
 *     (e.g. {@code "integration.user-service.process-event"}); {@code null} when the operation
 *     has no {@link dev.vertique.services.ServiceOperation} annotation or for {@link Kind#ROUTER}
 *     and {@link Kind#HANDLER} entries
 * @param targetOneWay {@code true} if the target operation is fire-and-forget
 * @param routes route entries for {@link Kind#ROUTER}; empty for other kinds
 * @param handler custom record handler for {@link Kind#HANDLER}; {@code null} for other kinds
 * @param deserializer effective deserializer selected by {@link KafkaConsumerValidation};
 *     {@code null} for {@link Kind#ROUTER} and for consumers with no value type
 * @param frameworkOwnedDeserializer {@code true} when {@code deserializer} was built by the serde
 *     registry (framework-owned, safe to close); {@code false} for a user-provided Model-2 custom
 *     deserializer (user-owned) or when there is no deserializer
 * @param filter pre-deserialization filter; {@code null} means accept-all
 * @param valueFormat the effective resolved value format (e.g. {@code "json"} or {@code "avro"})
 *     as determined by {@link KafkaConsumerValidation#validateAndBuild}; the single format resolved
 *     across a router's non-{@code Void} routes for {@link Kind#ROUTER}
 */
record ConsumerEntry(
        String name,
        ResolvedKafkaConsumerConfig config,
        Kind kind,
        @Nullable Class<?> valueType,
        @Nullable String targetAddress,
        @Nullable String stableTargetId,
        boolean targetOneWay,
        List<RouteEntry> routes,
        @Nullable KafkaRecordHandler<?> handler,
        @Nullable KafkaDeserializer<?> deserializer,
        boolean frameworkOwnedDeserializer,
        @Nullable KafkaRecordFilter filter,
        String valueFormat) {

    // --- Nested Types ---

    /**
     * Consumption model kind.
     */
    enum Kind {
        /** Model 1 / 2: single event bus target resolved from {@code @KafkaSource} or {@link KafkaConsumerBinding}. */
        BINDING,
        /** Model 3: declarative routing interface with {@link KafkaHandler}-annotated methods. */
        ROUTER,
        /** Model 4: custom {@link KafkaRecordHandler} implementation. */
        HANDLER
    }

    /**
     * A single routing rule within a {@link Kind#ROUTER} consumer entry.
     *
     * @param matchHeader header name to match against (empty if not header-based)
     * @param matchProperty JSON property name to match against (empty if not property-based)
     * @param matchValue value to compare against the header or property
     * @param defaultHandler {@code true} if this route catches all unmatched records
     * @param valueType the deserialization target type for this route
     * @param targetAddress event bus address to dispatch to; {@code null} if no dispatch
     * @param stableTargetId durable dot-delimited stable target id for the resolved operation;
     *     {@code null} when the target operation has no {@link dev.vertique.services.ServiceOperation}
     *     annotation or when no {@link DispatchTo} is present
     * @param targetOneWay {@code true} if the target operation is fire-and-forget
     */
    record RouteEntry(
            String matchHeader,
            String matchProperty,
            String matchValue,
            boolean defaultHandler,
            Class<?> valueType,
            @Nullable String targetAddress,
            @Nullable String stableTargetId,
            boolean targetOneWay) {}
}
