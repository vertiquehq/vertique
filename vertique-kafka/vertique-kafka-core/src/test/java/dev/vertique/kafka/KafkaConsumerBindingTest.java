// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link KafkaConsumerBinding} builder: default values, custom value overrides,
 * and accessor correctness.
 */
class KafkaConsumerBindingTest {

    record TestEvent(String name) {}

    // --- Default values ---

    @Nested
    @DisplayName("Builder default values")
    class DefaultValues {

        @Test
        @DisplayName("default errorStrategy is SKIP")
        void defaultErrorStrategyIsSkip() {
            KafkaConsumerBinding<TestEvent> binding = KafkaConsumerBinding.builder("test-binding", TestEvent.class)
                    .topic("test.topic")
                    .groupId("test-group")
                    .build();

            assertEquals(ErrorStrategy.SKIP, binding.errorStrategy());
        }

        @Test
        @DisplayName("default commitStrategy is AUTO")
        void defaultCommitStrategyIsAuto() {
            KafkaConsumerBinding<TestEvent> binding = KafkaConsumerBinding.builder("test-binding", TestEvent.class)
                    .topic("test.topic")
                    .groupId("test-group")
                    .build();

            assertEquals(CommitStrategy.AUTO, binding.commitStrategy());
        }

        @Test
        @DisplayName("default enabled is true")
        void defaultEnabledIsTrue() {
            KafkaConsumerBinding<TestEvent> binding = KafkaConsumerBinding.builder("test-binding", TestEvent.class)
                    .topic("test.topic")
                    .groupId("test-group")
                    .build();

            assertTrue(binding.enabled());
        }

        @Test
        @DisplayName("default eventBusTimeoutMs is 30000 ms")
        void defaultEventBusTimeoutMsIsThirtySeconds() {
            KafkaConsumerBinding<TestEvent> binding = KafkaConsumerBinding.builder("test-binding", TestEvent.class)
                    .topic("test.topic")
                    .groupId("test-group")
                    .build();

            assertEquals(30_000L, binding.eventBusTimeoutMs());
        }

        @Test
        @DisplayName("default deserializer is null")
        void defaultDeserializerIsNull() {
            KafkaConsumerBinding<TestEvent> binding = KafkaConsumerBinding.builder("test-binding", TestEvent.class)
                    .topic("test.topic")
                    .groupId("test-group")
                    .build();

            assertNull(binding.deserializer());
        }

        @Test
        @DisplayName("default filter is null")
        void defaultFilterIsNull() {
            KafkaConsumerBinding<TestEvent> binding = KafkaConsumerBinding.builder("test-binding", TestEvent.class)
                    .topic("test.topic")
                    .groupId("test-group")
                    .build();

            assertNull(binding.filter());
        }

        @Test
        @DisplayName("default deadLetterTopic is empty string")
        void defaultDeadLetterTopicIsEmptyString() {
            KafkaConsumerBinding<TestEvent> binding = KafkaConsumerBinding.builder("test-binding", TestEvent.class)
                    .topic("test.topic")
                    .groupId("test-group")
                    .build();

            assertEquals("", binding.deadLetterTopic());
        }
    }

    // --- Builder sets all values ---

    @Nested
    @DisplayName("Builder with custom values")
    class CustomValues {

        @Test
        @DisplayName("builder sets name and valueType correctly")
        void builderSetsNameAndValueType() {
            KafkaConsumerBinding<TestEvent> binding = KafkaConsumerBinding.builder("my-binding", TestEvent.class)
                    .topic("test.topic")
                    .groupId("test-group")
                    .build();

            assertEquals("my-binding", binding.name());
            assertEquals(TestEvent.class, binding.valueType());
        }

        @Test
        @DisplayName("builder sets topic correctly")
        void builderSetsTopic() {
            KafkaConsumerBinding<TestEvent> binding = KafkaConsumerBinding.builder("binding", TestEvent.class)
                    .topic("my.custom.topic")
                    .groupId("group")
                    .build();

            assertEquals("my.custom.topic", binding.topic());
        }

        @Test
        @DisplayName("builder sets groupId correctly")
        void builderSetsGroupId() {
            KafkaConsumerBinding<TestEvent> binding = KafkaConsumerBinding.builder("binding", TestEvent.class)
                    .topic("topic")
                    .groupId("my-group-id")
                    .build();

            assertEquals("my-group-id", binding.groupId());
        }

        @Test
        @DisplayName("builder with dispatchTo sets targetService and targetOperation")
        void builderSetsDispatchTarget() {
            KafkaConsumerBinding<TestEvent> binding = KafkaConsumerBinding.builder("binding", TestEvent.class)
                    .topic("topic")
                    .groupId("group")
                    .dispatchTo(String.class, "process")
                    .build();

            assertEquals(String.class, binding.targetService());
            assertEquals("process", binding.targetOperation());
        }

        @Test
        @DisplayName("builder overrides errorStrategy")
        void builderOverridesErrorStrategy() {
            KafkaConsumerBinding<TestEvent> binding = KafkaConsumerBinding.builder("binding", TestEvent.class)
                    .topic("topic")
                    .groupId("group")
                    .errorStrategy(ErrorStrategy.DEAD_LETTER)
                    .build();

            assertEquals(ErrorStrategy.DEAD_LETTER, binding.errorStrategy());
        }

        @Test
        @DisplayName("builder overrides commitStrategy")
        void builderOverridesCommitStrategy() {
            KafkaConsumerBinding<TestEvent> binding = KafkaConsumerBinding.builder("binding", TestEvent.class)
                    .topic("topic")
                    .groupId("group")
                    .commitStrategy(CommitStrategy.MANUAL)
                    .build();

            assertEquals(CommitStrategy.MANUAL, binding.commitStrategy());
        }

        @Test
        @DisplayName("builder overrides enabled to false")
        void builderOverridesEnabledToFalse() {
            KafkaConsumerBinding<TestEvent> binding = KafkaConsumerBinding.builder("binding", TestEvent.class)
                    .topic("topic")
                    .groupId("group")
                    .enabled(false)
                    .build();

            assertFalse(binding.enabled());
        }

        @Test
        @DisplayName("builder overrides eventBusTimeoutMs")
        void builderOverridesEventBusTimeoutMs() {
            KafkaConsumerBinding<TestEvent> binding = KafkaConsumerBinding.builder("binding", TestEvent.class)
                    .topic("topic")
                    .groupId("group")
                    .eventBusTimeoutMs(5_000L)
                    .build();

            assertEquals(5_000L, binding.eventBusTimeoutMs());
        }

        @Test
        @DisplayName("builder sets deadLetterTopic")
        void builderSetsDeadLetterTopic() {
            KafkaConsumerBinding<TestEvent> binding = KafkaConsumerBinding.builder("binding", TestEvent.class)
                    .topic("topic")
                    .groupId("group")
                    .deadLetterTopic("my.dlq.topic")
                    .build();

            assertEquals("my.dlq.topic", binding.deadLetterTopic());
        }

        @Test
        @DisplayName("builder sets custom filter")
        void builderSetsFilter() {
            KafkaRecordFilter filter = KafkaRecordFilter.headerExists("event-type");
            KafkaConsumerBinding<TestEvent> binding = KafkaConsumerBinding.builder("binding", TestEvent.class)
                    .topic("topic")
                    .groupId("group")
                    .filter(filter)
                    .build();

            assertSame(filter, binding.filter());
        }

        @Test
        @DisplayName("kafkaConsumerBinding_jsonProfileBuilderAndAccessor: jsonProfile(...) sets the profile;"
                + " jsonProfile() returns it")
        void kafkaConsumerBinding_jsonProfileBuilderAndAccessor() {
            dev.vertique.core.json.JsonProfileId id = dev.vertique.core.json.JsonProfileId.of("orders-v2");
            KafkaConsumerBinding<TestEvent> binding = KafkaConsumerBinding.builder("binding", TestEvent.class)
                    .topic("topic")
                    .groupId("group")
                    .jsonProfile(id)
                    .build();

            assertEquals(id, binding.jsonProfile(), "the harmonized jsonProfile() accessor must return the set id");
        }
    }
}
