// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import dev.vertique.core.json.JsonProfileId;
import dev.vertique.kafka.serialization.KafkaDeserializer;
import jakarta.annotation.Nullable;

/**
 * Immutable Kafka consumer binding (Model 2) that maps a topic to a service operation.
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
    private final ErrorStrategy errorStrategy;
    private final CommitStrategy commitStrategy;
    private final String deadLetterTopic;
    private final KafkaDeserializer<V> deserializer;
    private final KafkaRecordFilter filter;
    private final boolean enabled;
    private final long eventBusTimeoutMs;
    private final JsonProfileId jsonProfile;

    private KafkaConsumerBinding(Builder<V> builder) {
        this.name = builder.name;
        this.valueType = builder.valueType;
        this.topic = builder.topic;
        this.groupId = builder.groupId;
        this.targetService = builder.targetService;
        this.targetOperation = builder.targetOperation;
        this.errorStrategy = builder.errorStrategy;
        this.commitStrategy = builder.commitStrategy;
        this.deadLetterTopic = builder.deadLetterTopic;
        this.deserializer = builder.deserializer;
        this.filter = builder.filter;
        this.enabled = builder.enabled;
        this.eventBusTimeoutMs = builder.eventBusTimeoutMs;
        this.jsonProfile = builder.jsonProfile;
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
     * {@code null} to use the framework default (the {@code vertx} profile backed by
     * {@code DatabindCodec.mapper()}).
     *
     * @return the profile id, or {@code null}
     */
    @Nullable
    public JsonProfileId jsonProfile() {
        return jsonProfile;
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
        private ErrorStrategy errorStrategy = ErrorStrategy.SKIP;
        private CommitStrategy commitStrategy = CommitStrategy.AUTO;
        private String deadLetterTopic = "";
        private KafkaDeserializer<V> deserializer;
        private KafkaRecordFilter filter;
        private boolean enabled = true;
        private long eventBusTimeoutMs = 30_000L;
        private JsonProfileId jsonProfile;

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
         * {@code null} (the default), the framework uses the {@code vertx} profile backed by
         * {@code DatabindCodec.mapper()}.
         *
         * @param jsonProfile the profile id, or {@code null} for the framework default
         * @return this builder
         */
        public Builder<V> jsonProfile(@Nullable JsonProfileId jsonProfile) {
            this.jsonProfile = jsonProfile;
            return this;
        }

        /**
         * Builds the binding.
         *
         * @return the immutable binding
         */
        public KafkaConsumerBinding<V> build() {
            return new KafkaConsumerBinding<>(this);
        }
    }
}
