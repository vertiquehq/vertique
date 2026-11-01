// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import dev.vertique.core.json.JsonProfileId;
import dev.vertique.kafka.serialization.KafkaDeserializer;
import jakarta.annotation.Nullable;
import java.util.Objects;

/**
 * Immutable Kafka consumer binding (Model 2) that maps a topic to a service operation, or to a
 * {@link KafkaRecordHandler} that receives each decoded record.
 *
 * <p>Contributed via Dagger multibinding:
 * <pre>{@code
 * @Provides @IntoSet
 * static KafkaConsumerBinding<?> orderBinding() {
 *     return KafkaConsumerBinding.builder("order-created", OrderCreatedEvent.class)
 *         .topic("order.created")
 *         .groupId("order-processing")
 *         .dispatchTo(OrderService.class, "processOrder")
 *         .build();
 * }
 * }</pre>
 *
 * <p>A binding delivers to a {@code dispatchTo} target service or to a {@link Builder#handler
 * handler}, never both: {@link Builder#build()} refuses a binding that names both. A binding that
 * names neither builds, and the consumer scanner refuses it at registration.
 *
 * @param <V> the value type for deserialization
 */
public class KafkaConsumerBinding<V> {

    // --- Fields ---

    private final String name;
    private final Class<V> valueType;
    private final String topic;
    private final String groupId;
    private final Class<?> targetService;
    private final String targetOperation;
    private final KafkaRecordHandler<V> handler;
    private final ErrorStrategy errorStrategy;
    private final CommitStrategy commitStrategy;
    private final String deadLetterTopic;
    private final KafkaDeserializer<V> deserializer;
    private final KafkaRecordFilter filter;
    private final boolean enabled;
    private final long eventBusTimeoutMs;
    private final JsonProfileId jsonProfile;
    private final Runnable ignoreUndecodable;
    private final boolean noHeaderContext;
    private final boolean forceStrategies;
    private final KafkaDispatchGate dispatchGate;

    private KafkaConsumerBinding(Builder<V> builder) {
        this.name = builder.name;
        this.valueType = builder.valueType;
        this.topic = builder.topic;
        this.groupId = builder.groupId;
        this.targetService = builder.targetService;
        this.targetOperation = builder.targetOperation;
        this.handler = builder.handler;
        this.errorStrategy = builder.errorStrategy;
        this.commitStrategy = builder.commitStrategy;
        this.deadLetterTopic = builder.deadLetterTopic;
        this.deserializer = builder.deserializer;
        this.filter = builder.filter;
        this.enabled = builder.enabled;
        this.eventBusTimeoutMs = builder.eventBusTimeoutMs;
        this.jsonProfile = builder.jsonProfile;
        this.ignoreUndecodable = builder.ignoreUndecodable;
        this.noHeaderContext = builder.noHeaderContext;
        this.forceStrategies = builder.forceStrategies;
        this.dispatchGate = builder.dispatchGate;
    }

    // --- Accessors ---

    /**
     * Returns the binding name for config reference.
     *
     * @return the binding name
     */
    public String name() {
        return name;
    }

    /**
     * Returns the deserialization target type.
     *
     * @return the value type class
     */
    public Class<V> valueType() {
        return valueType;
    }

    /**
     * Returns the Kafka topic to consume from.
     *
     * @return the topic name
     */
    public String topic() {
        return topic;
    }

    /**
     * Returns the consumer group ID.
     *
     * @return the consumer group identifier
     */
    public String groupId() {
        return groupId;
    }

    /**
     * Returns the target service contract interface.
     *
     * @return the service contract class
     */
    public Class<?> targetService() {
        return targetService;
    }

    /**
     * Returns the target operation name on the service contract.
     *
     * @return the operation name
     */
    public String targetOperation() {
        return targetOperation;
    }

    /**
     * Returns the record handler that receives each decoded record, or {@code null} when the
     * binding dispatches to a target service instead.
     *
     * @return the handler, or {@code null}
     */
    @Nullable
    public KafkaRecordHandler<V> handler() {
        return handler;
    }

    /**
     * Returns the error handling strategy.
     *
     * @return the error strategy
     */
    public ErrorStrategy errorStrategy() {
        return errorStrategy;
    }

    /**
     * Returns the offset commit strategy.
     *
     * @return the commit strategy
     */
    public CommitStrategy commitStrategy() {
        return commitStrategy;
    }

    /**
     * Returns the dead-letter topic name. Empty string means default ({@code "{topic}.dlq"}).
     *
     * @return the DLQ topic name, or empty for default
     */
    public String deadLetterTopic() {
        return deadLetterTopic;
    }

    /**
     * Returns the custom deserializer, or {@code null} to use the resolved-format default deserializer.
     *
     * @return the deserializer, or {@code null}
     */
    @Nullable
    public KafkaDeserializer<V> deserializer() {
        return deserializer;
    }

    /**
     * Returns the pre-deserialization filter, or {@code null} for accept-all.
     *
     * @return the filter, or {@code null}
     */
    @Nullable
    public KafkaRecordFilter filter() {
        return filter;
    }

    /**
     * Returns whether this consumer is enabled.
     *
     * @return {@code true} if enabled
     */
    public boolean enabled() {
        return enabled;
    }

    /**
     * Returns the event bus send timeout in milliseconds.
     *
     * @return the timeout in milliseconds
     */
    public long eventBusTimeoutMs() {
        return eventBusTimeoutMs;
    }

    /**
     * Returns the JSON mapper profile id for framework-managed value deserialization, or
     * {@code null} to use the framework default (the {@code vertique} profile resolved through the
     * registry, floored by {@code json.jsonProfile}).
     *
     * @return the profile id, or {@code null}
     */
    @Nullable
    public JsonProfileId jsonProfile() {
        return jsonProfile;
    }

    /**
     * Returns the callback that runs when a record fails to decode and is ignored, or {@code null}
     * when an undecodable record goes down the error strategy.
     *
     * @return the callback, or {@code null}
     */
    @Nullable
    public Runnable ignoreUndecodable() {
        return ignoreUndecodable;
    }

    /**
     * Returns whether the handler runs without a durable context installed from the record's
     * headers.
     *
     * @return {@code true} if no header context is installed
     */
    public boolean noHeaderContext() {
        return noHeaderContext;
    }

    /**
     * Returns whether registration requires the resolved commit and error strategies to equal this
     * binding's and the resolved exhausted strategy to be {@link ErrorStrategy#DEAD_LETTER}.
     *
     * @return {@code true} if the strategies are forced
     */
    public boolean forceStrategies() {
        return forceStrategies;
    }

    /**
     * Returns the gate that says whether a record may start.
     *
     * @return the gate, or {@code null} for none
     */
    @Nullable
    public KafkaDispatchGate dispatchGate() {
        return dispatchGate;
    }

    /**
     * Creates a new builder.
     *
     * @param name the binding name (used as config reference key)
     * @param valueType the deserialization target type
     * @param <V> the value type
     * @return the builder
     */
    public static <V> Builder<V> builder(String name, Class<V> valueType) {
        return new Builder<>(name, valueType);
    }

    /**
     * Builder for {@link KafkaConsumerBinding}.
     *
     * @param <V> the value type
     */
    public static class Builder<V> {

        private final String name;
        private final Class<V> valueType;
        private String topic;
        private String groupId;
        private Class<?> targetService;
        private String targetOperation;
        private KafkaRecordHandler<V> handler;
        private ErrorStrategy errorStrategy = ErrorStrategy.SKIP;
        private CommitStrategy commitStrategy = CommitStrategy.AUTO;
        private String deadLetterTopic = "";
        private KafkaDeserializer<V> deserializer;
        private KafkaRecordFilter filter;
        private boolean enabled = true;
        private long eventBusTimeoutMs = 30_000L;
        private JsonProfileId jsonProfile;
        private Runnable ignoreUndecodable;
        private boolean noHeaderContext;
        private boolean forceStrategies;
        private KafkaDispatchGate dispatchGate;

        private Builder(String name, Class<V> valueType) {
            this.name = name;
            this.valueType = valueType;
        }

        /**
         * Sets the Kafka topic.
         *
         * @param topic the topic name
         * @return this builder
         */
        public Builder<V> topic(String topic) {
            this.topic = topic;
            return this;
        }

        /**
         * Sets the consumer group ID.
         *
         * @param groupId the consumer group identifier
         * @return this builder
         */
        public Builder<V> groupId(String groupId) {
            this.groupId = groupId;
            return this;
        }

        /**
         * Sets the target service and operation for dispatch.
         *
         * <p>For operations annotated with {@link dev.vertique.services.ServiceOperation}, pass the
         * durable operation id (the {@code @ServiceOperation} value) so that
         * {@link dev.vertique.services.ServiceTargetResolver} can resolve the stable target and
         * populate {@link ConsumerEntry#stableTargetId()}. Using the durable id ensures this
         * reference survives Java method renames.
         *
         * <p>For operations without {@code @ServiceOperation}, pass the Java method name.
         *
         * @param service the service contract interface class
         * @param operation the durable operation id, or the Java method name for legacy operations
         * @return this builder
         */
        public Builder<V> dispatchTo(Class<?> service, String operation) {
            this.targetService = service;
            this.targetOperation = operation;
            return this;
        }

        /**
         * Sets a record handler as the delivery target, as an alternative to
         * {@link #dispatchTo(Class, String)}. The record is decoded to {@code V} and passed to the
         * handler as a {@link KafkaMessage} carrying its key, topic, partition, offset, timestamp and
         * headers. The binding keeps its filter, commit strategy, error and retry strategy,
         * dead-letter topic and interceptors, and the handler runs with the record's durable context
         * installed.
         *
         * <p>A handler and a {@code dispatchTo} target are mutually exclusive: {@link #build()}
         * refuses a binding that names both.
         *
         * @param handler the handler that receives each decoded record
         * @return this builder
         */
        public Builder<V> handler(KafkaRecordHandler<V> handler) {
            this.handler = handler;
            return this;
        }

        /**
         * Sets the error handling strategy.
         *
         * @param errorStrategy the error strategy
         * @return this builder
         */
        public Builder<V> errorStrategy(ErrorStrategy errorStrategy) {
            this.errorStrategy = errorStrategy;
            return this;
        }

        /**
         * Sets the offset commit strategy.
         *
         * @param commitStrategy the commit strategy
         * @return this builder
         */
        public Builder<V> commitStrategy(CommitStrategy commitStrategy) {
            this.commitStrategy = commitStrategy;
            return this;
        }

        /**
         * Sets the dead-letter topic name.
         *
         * @param deadLetterTopic the DLQ topic name, or empty for default ({@code "{topic}.dlq"})
         * @return this builder
         */
        public Builder<V> deadLetterTopic(String deadLetterTopic) {
            this.deadLetterTopic = deadLetterTopic;
            return this;
        }

        /**
         * Sets a custom deserializer.
         *
         * @param deserializer the deserializer to use instead of the resolved-format default
         * @return this builder
         */
        public Builder<V> deserializer(KafkaDeserializer<V> deserializer) {
            this.deserializer = deserializer;
            return this;
        }

        /**
         * Sets a pre-deserialization filter.
         *
         * @param filter the filter to apply before deserialization
         * @return this builder
         */
        public Builder<V> filter(KafkaRecordFilter filter) {
            this.filter = filter;
            return this;
        }

        /**
         * Sets whether this consumer is enabled.
         *
         * @param enabled {@code true} to enable, {@code false} to disable
         * @return this builder
         */
        public Builder<V> enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        /**
         * Sets the event bus send timeout in milliseconds.
         *
         * @param eventBusTimeoutMs the timeout in milliseconds
         * @return this builder
         */
        public Builder<V> eventBusTimeoutMs(long eventBusTimeoutMs) {
            this.eventBusTimeoutMs = eventBusTimeoutMs;
            return this;
        }

        /**
         * Sets the JSON mapper profile id for framework-managed value deserialization. When
         * {@code null} (the default), the framework uses the {@code vertique} profile resolved
         * through the registry, floored by {@code json.jsonProfile}.
         *
         * @param jsonProfile the profile id, or {@code null} for the framework default
         * @return this builder
         */
        public Builder<V> jsonProfile(@Nullable JsonProfileId jsonProfile) {
            this.jsonProfile = jsonProfile;
            return this;
        }

        /**
         * Makes the consumer ignore a record that fails to decode. The record is acknowledged,
         * its offset is committed when the commit strategy is {@link CommitStrategy#MANUAL}, its
         * outcome is reported as skipped, and the callback runs; the record does not go down the
         * error strategy. Every deserializer failure is ignored, and nothing else: a handler
         * failure on a decodable record is handled as usual. A callback that throws is logged by
         * class and does not change the outcome. Applies to a binding with a
         * {@link #handler(KafkaRecordHandler) handler}.
         *
         * @param onIgnored the callback to run for each ignored record
         * @return this builder
         */
        public Builder<V> ignoreUndecodable(Runnable onIgnored) {
            this.ignoreUndecodable = Objects.requireNonNull(onIgnored, "onIgnored");
            return this;
        }

        /**
         * Makes the handler run without a durable context installed from the record's headers.
         * Applies to a binding with a {@link #handler(KafkaRecordHandler) handler}.
         *
         * @return this builder
         */
        public Builder<V> noHeaderContext() {
            this.noHeaderContext = true;
            return this;
        }

        /**
         * Makes registration fail, naming this consumer and the option, unless the resolved
         * {@code commitStrategy} and {@code errorStrategy} equal this binding's and the resolved
         * {@code retry.exhaustedStrategy} is {@link ErrorStrategy#DEAD_LETTER}, so that
         * {@code kafka.consumers.{name}.*} cannot change them. A new consumer group starts at the beginning of the
         * topic unless {@code auto.offset.reset} is configured. The consumer handles records concurrently, up to
         * {@code maxInFlight} at once, like any other {@link CommitStrategy#MANUAL} consumer. The binding also needs
         * {@link CommitStrategy#MANUAL}: the default {@link CommitStrategy#AUTO} could commit past a record whose
         * failure is unsettled. Applies to a binding with a {@link #handler(KafkaRecordHandler) handler}.
         *
         * @return this builder
         */
        public Builder<V> forceStrategies() {
            this.forceStrategies = true;
            return this;
        }

        /**
         * Sets the gate that says whether a record may start. While the gate is closed no record starts, no partition
         * fetches and the consumer keeps polling; a record the gate held back has not been attempted, so no retry is
         * spent. The binding needs {@link CommitStrategy#MANUAL}.
         *
         * @param dispatchGate the gate
         * @return this builder
         * @see KafkaDispatchGate
         */
        public Builder<V> dispatchGate(KafkaDispatchGate dispatchGate) {
            this.dispatchGate = Objects.requireNonNull(dispatchGate, "dispatchGate");
            return this;
        }

        /**
         * Builds the binding.
         *
         * @return the immutable binding
         * @throws IllegalStateException if the binding names both a {@link #handler handler} and a
         *     {@link #dispatchTo(Class, String) dispatchTo} target, or names no handler and sets
         *     {@link #ignoreUndecodable}, {@link #noHeaderContext} or {@link #forceStrategies}, or
         *     sets {@link #forceStrategies} or {@link #dispatchGate} without {@link CommitStrategy#MANUAL}
         */
        public KafkaConsumerBinding<V> build() {
            if (handler != null && targetService != null) {
                throw new IllegalStateException("KafkaConsumerBinding '" + name
                        + "' names both a handler and a dispatchTo target; name one or the other");
            }
            if (handler == null && (ignoreUndecodable != null || noHeaderContext || forceStrategies)) {
                throw new IllegalStateException("KafkaConsumerBinding '" + name
                        + "' sets ignoreUndecodable, noHeaderContext or forceStrategies, which apply to a handler"
                        + " only; name a handler");
            }
            if (forceStrategies && commitStrategy != CommitStrategy.MANUAL) {
                throw new IllegalStateException("KafkaConsumerBinding '" + name
                        + "' sets forceStrategies, which needs commitStrategy MANUAL but has " + commitStrategy
                        + "; close-time auto-commit could pass a record whose failure is unsettled");
            }
            if (dispatchGate != null && commitStrategy != CommitStrategy.MANUAL) {
                throw new IllegalStateException("KafkaConsumerBinding '" + name
                        + "' sets a dispatchGate, which needs commitStrategy MANUAL but has " + commitStrategy);
            }
            return new KafkaConsumerBinding<>(this);
        }
    }
}
