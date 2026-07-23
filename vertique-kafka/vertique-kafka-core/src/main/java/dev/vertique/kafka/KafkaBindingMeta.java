// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import jakarta.annotation.Nullable;
import java.util.List;
import java.util.Objects;

/**
 * Precomputed, compile-time-knowable Kafka consumer binding metadata emitted by the
 * {@code vertique-codegen-kafka} annotation processor into {@code {Consumer}_BindingMeta} companions.
 *
 * <p>Carries ONLY static data. Runtime target resolution (event-bus address, stable target id, one-way
 * flag) is intentionally NOT here — it is derived at boot by {@code ServiceTargetResolver}, the same source
 * of truth used by the reflective {@code KafkaConsumerScanner}. See ADR-0072.
 *
 * <p>One origin consumer class can produce several bindings (a {@code @KafkaSource} service-impl class may
 * carry several {@code @KafkaSource} methods), so a generated companion exposes a {@code List} of these.
 *
 * <p>The three {@link Kind} values map to the three consumption models:
 * <ul>
 *   <li>{@link Kind#SOURCE} — Model 1: {@code @KafkaSource} on a service-implementation method</li>
 *   <li>{@link Kind#ROUTER} — Model 3: {@code @KafkaListener} interface with {@code @KafkaHandler} routes</li>
 *   <li>{@link Kind#HANDLER} — Model 4: {@code @KafkaListener} class implementing {@code KafkaRecordHandler<V>}</li>
 * </ul>
 *
 * @param name            binding name (config reference key); never blank
 * @param topic           Kafka topic; never blank
 * @param groupId         consumer group id; never blank
 * @param kind            {@link Kind#SOURCE}, {@link Kind#HANDLER}, or {@link Kind#ROUTER}
 * @param valueType       deserialization target for {@link Kind#HANDLER}; {@code null} for
 *                        {@link Kind#SOURCE} (resolved at runtime from {@code ServiceMethodMeta})
 *                        and {@link Kind#ROUTER}
 * @param errorStrategy    resolved error strategy
 * @param commitStrategy   resolved commit strategy
 * @param deadLetterTopic  dead-letter topic; {@code ""} means the default {@code "{topic}.dlq"}
 * @param jsonProfile      {@link Kind#ROUTER} and {@link Kind#HANDLER} only: the
 *                         {@code @JsonProfile} default applied when the
 *                         per-consumer config does not set {@code jsonProfile};
 *                         {@code null}/blank means the framework default ({@code vertx}).
 *                         Always {@code null} for {@link Kind#SOURCE} (which has no listener
 *                         annotation), matching the reflective {@code @KafkaSource} path.
 * @param targetOperation  {@link Kind#SOURCE} only: the service-implementation method name used to
 *                         locate the {@code ServiceMethodMeta} at boot; {@code null} for
 *                         {@link Kind#ROUTER} and {@link Kind#HANDLER}
 * @param routes           {@link Kind#ROUTER} only: ordered route metadata;
 *                         empty for {@link Kind#SOURCE} and {@link Kind#HANDLER}
 */
public record KafkaBindingMeta(
        String name,
        String topic,
        String groupId,
        Kind kind,
        @Nullable Class<?> valueType,
        ErrorStrategy errorStrategy,
        CommitStrategy commitStrategy,
        String deadLetterTopic,
        @Nullable String jsonProfile,
        @Nullable String targetOperation,
        List<RouteMeta> routes) {

    /**
     * Validates the kind-specific invariants and defensively copies {@code routes}.
     *
     * <p>Invariants:
     * <ul>
     *   <li>{@link Kind#ROUTER} requires non-empty routes and a null {@code valueType}.</li>
     *   <li>{@link Kind#SOURCE} and {@link Kind#HANDLER} require empty routes.</li>
     * </ul>
     */
    public KafkaBindingMeta {
        routes = List.copyOf(routes);
        if (kind == Kind.ROUTER) {
            if (valueType != null || routes.isEmpty()) {
                throw new IllegalArgumentException("ROUTER binding requires null valueType and non-empty routes");
            }
        } else if (!routes.isEmpty()) {
            throw new IllegalArgumentException("SOURCE and HANDLER bindings must have empty routes");
        }
    }

    /**
     * Binding model discriminator — identifies which of the three consumption models this
     * metadata entry represents.
     */
    public enum Kind {
        /**
         * Model 1: {@code @KafkaSource} on a service-implementation method.
         * Maps to {@link dev.vertique.kafka.ConsumerEntry.Kind#BINDING} at runtime.
         */
        SOURCE,
        /**
         * Model 3: {@code @KafkaListener} interface with {@code @KafkaHandler}-routed methods.
         * Maps to {@link dev.vertique.kafka.ConsumerEntry.Kind#ROUTER} at runtime.
         */
        ROUTER,
        /**
         * Model 4: {@code @KafkaListener} class implementing {@code KafkaRecordHandler<V>}.
         * Maps to {@link dev.vertique.kafka.ConsumerEntry.Kind#HANDLER} at runtime.
         */
        HANDLER
    }

    /**
     * Precomputed route metadata for a {@link Kind#ROUTER} binding (Model 3).
     *
     * @param matchHeader     header name to match, or {@code ""}
     * @param matchProperty   JSON property to match, or {@code ""}
     * @param matchValue      value to compare against the header/property
     * @param defaultHandler  {@code true} for the catch-all route
     * @param valueType       per-route deserialization target; never {@code null} (use {@code Void.class})
     * @param targetService   target service contract class, or {@code null} when no {@code @DispatchTo}
     * @param targetOperation durable operation id / method name, or {@code null} when no {@code @DispatchTo}
     */
    public record RouteMeta(
            String matchHeader,
            String matchProperty,
            String matchValue,
            boolean defaultHandler,
            Class<?> valueType,
            @Nullable Class<?> targetService,
            @Nullable String targetOperation) {

        /** Validates that {@code valueType} is non-null. */
        public RouteMeta {
            Objects.requireNonNull(valueType, "route valueType must not be null (use Void.class)");
        }
    }
}
