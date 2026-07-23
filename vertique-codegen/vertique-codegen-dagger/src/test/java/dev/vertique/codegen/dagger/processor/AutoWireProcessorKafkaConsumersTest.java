// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.dagger.processor;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests that {@link AutoWireProcessor} generates {@code GeneratedKafkaConsumersModule} containing
 * {@code @Provides @IntoSet @KafkaConsumers Object} methods for classes annotated with
 * {@code @KafkaListener} or {@code @KafkaSource}.
 *
 * <p>Both markers are handled by a single collector; their bindings are unioned into the same
 * generated module.
 */
class AutoWireProcessorKafkaConsumersTest {

    private static final String GENERATED_MODULE_FQN = "dev.vertique.examples.kafka.GeneratedKafkaConsumersModule";

    private static final String KAFKA_LISTENER_ANNOTATION = """
            package dev.vertique.kafka;

            import java.lang.annotation.*;

            @Target(ElementType.TYPE)
            @Retention(RetentionPolicy.RUNTIME)
            @Documented
            public @interface KafkaListener {
                String topic() default "";
                String groupId() default "";
            }
            """;

    private static final String KAFKA_SOURCE_ANNOTATION = """
            package dev.vertique.kafka;

            import java.lang.annotation.*;

            @Target(ElementType.TYPE)
            @Retention(RetentionPolicy.RUNTIME)
            @Documented
            public @interface KafkaSource {
                String topic() default "";
                String groupId() default "";
            }
            """;

    private static final String KAFKA_CONSUMERS_QUALIFIER = """
            package dev.vertique.kafka;

            import java.lang.annotation.*;
            import jakarta.inject.Qualifier;

            @Qualifier
            @Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD})
            @Retention(RetentionPolicy.RUNTIME)
            @Documented
            public @interface KafkaConsumers {}
            """;

    @Test
    @DisplayName("@KafkaListener class with @Inject constructor generates @KafkaConsumers multibinding")
    void kafkaListenerClass_withInjectConstructor_generatesBinding() {
        var result = ProcessorTestHarness.run(
                new AutoWireProcessor(),
                SourceFiles.inline("dev.vertique.kafka.KafkaListener", KAFKA_LISTENER_ANNOTATION),
                SourceFiles.inline("dev.vertique.kafka.KafkaSource", KAFKA_SOURCE_ANNOTATION),
                SourceFiles.inline("dev.vertique.kafka.KafkaConsumers", KAFKA_CONSUMERS_QUALIFIER),
                SourceFiles.inline("dev.vertique.examples.kafka.UserEventConsumer", """
                        package dev.vertique.examples.kafka;

                        import dev.vertique.kafka.KafkaListener;
                        import jakarta.inject.Inject;

                        @KafkaListener(topic = "user-events")
                        public class UserEventConsumer {
                            @Inject
                            UserEventConsumer() {}
                        }
                        """));

        result.assertSuccess();
        result.assertGeneratedSourceContains(GENERATED_MODULE_FQN, "@Module");
        result.assertGeneratedSourceContains(GENERATED_MODULE_FQN, "@IntoSet");
        result.assertGeneratedSourceContains(GENERATED_MODULE_FQN, "userEventConsumerBinding");
        result.assertGeneratedSourceContains(GENERATED_MODULE_FQN, "UserEventConsumer");
    }

    @Test
    @DisplayName("@KafkaSource class with @Inject constructor generates @KafkaConsumers multibinding")
    void kafkaSourceClass_withInjectConstructor_generatesBinding() {
        var result = ProcessorTestHarness.run(
                new AutoWireProcessor(),
                SourceFiles.inline("dev.vertique.kafka.KafkaListener", KAFKA_LISTENER_ANNOTATION),
                SourceFiles.inline("dev.vertique.kafka.KafkaSource", KAFKA_SOURCE_ANNOTATION),
                SourceFiles.inline("dev.vertique.kafka.KafkaConsumers", KAFKA_CONSUMERS_QUALIFIER),
                SourceFiles.inline("dev.vertique.examples.kafka.OrderEventSource", """
                        package dev.vertique.examples.kafka;

                        import dev.vertique.kafka.KafkaSource;
                        import jakarta.inject.Inject;

                        @KafkaSource(topic = "order-events")
                        public class OrderEventSource {
                            @Inject
                            OrderEventSource() {}
                        }
                        """));

        result.assertSuccess();
        result.assertGeneratedSourceContains(GENERATED_MODULE_FQN, "orderEventSourceBinding");
        result.assertGeneratedSourceContains(GENERATED_MODULE_FQN, "OrderEventSource");
    }

    @Test
    @DisplayName("both @KafkaListener and @KafkaSource classes appear in the same generated module")
    void bothMarkers_appearsInSameModule() {
        var result = ProcessorTestHarness.run(
                new AutoWireProcessor(),
                SourceFiles.inline("dev.vertique.kafka.KafkaListener", KAFKA_LISTENER_ANNOTATION),
                SourceFiles.inline("dev.vertique.kafka.KafkaSource", KAFKA_SOURCE_ANNOTATION),
                SourceFiles.inline("dev.vertique.kafka.KafkaConsumers", KAFKA_CONSUMERS_QUALIFIER),
                SourceFiles.inline("dev.vertique.examples.kafka.UserEventConsumer", """
                        package dev.vertique.examples.kafka;

                        import dev.vertique.kafka.KafkaListener;
                        import jakarta.inject.Inject;

                        @KafkaListener(topic = "user-events")
                        public class UserEventConsumer {
                            @Inject
                            UserEventConsumer() {}
                        }
                        """),
                SourceFiles.inline("dev.vertique.examples.kafka.OrderEventSource", """
                        package dev.vertique.examples.kafka;

                        import dev.vertique.kafka.KafkaSource;
                        import jakarta.inject.Inject;

                        @KafkaSource(topic = "order-events")
                        public class OrderEventSource {
                            @Inject
                            OrderEventSource() {}
                        }
                        """));

        result.assertSuccess();
        result.assertGeneratedSourceContains(GENERATED_MODULE_FQN, "userEventConsumerBinding");
        result.assertGeneratedSourceContains(GENERATED_MODULE_FQN, "orderEventSourceBinding");
    }
}
