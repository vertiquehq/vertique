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
 * {@code @KafkaListener}. A class carrying only {@code @KafkaSource} methods (Model 1) gets no
 * binding: the runtime finds those consumers through the service contract registry.
 */
class AutoWireProcessorKafkaConsumersTest {

    private static final String GENERATED_MODULE_FQN = "dev.vertique.examples.kafka.GeneratedKafkaConsumersModule";

    private static final String KAFKA_LISTENER_ANNOTATION = """
            package dev.vertique.kafka;

            import java.lang.annotation.Documented;
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;

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

            import java.lang.annotation.Documented;
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;

            @Target(ElementType.METHOD)
            @Retention(RetentionPolicy.RUNTIME)
            @Documented
            public @interface KafkaSource {
                String topic() default "";
                String groupId() default "";
            }
            """;

    private static final String KAFKA_CONSUMERS_QUALIFIER = """
            package dev.vertique.kafka;

            import java.lang.annotation.Documented;
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;
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
    @DisplayName("a class with a @KafkaSource method gets no @KafkaConsumers binding; Model 1 is found at runtime")
    void kafkaSourceMethod_generatesNoBinding() {
        var result = ProcessorTestHarness.run(
                new AutoWireProcessor(),
                SourceFiles.inline("dev.vertique.kafka.KafkaListener", KAFKA_LISTENER_ANNOTATION),
                SourceFiles.inline("dev.vertique.kafka.KafkaSource", KAFKA_SOURCE_ANNOTATION),
                SourceFiles.inline("dev.vertique.kafka.KafkaConsumers", KAFKA_CONSUMERS_QUALIFIER),
                SourceFiles.inline("dev.vertique.examples.kafka.OrderService", """
                        package dev.vertique.examples.kafka;

                        import dev.vertique.kafka.KafkaSource;
                        import jakarta.inject.Inject;

                        public class OrderService {
                            @Inject
                            OrderService() {}

                            @KafkaSource(topic = "order-events")
                            public void onOrder(String payload) {}
                        }
                        """));

        result.assertSuccess();
        org.junit.jupiter.api.Assertions.assertTrue(
                result.compilation().generatedSourceFile(GENERATED_MODULE_FQN).isEmpty());
    }

    @Test
    @DisplayName("a @KafkaListener class is bound while a sibling class with a @KafkaSource method is not")
    void listenerBound_sourceMethodClassNotBound() {
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
                SourceFiles.inline("dev.vertique.examples.kafka.OrderService", """
                        package dev.vertique.examples.kafka;

                        import dev.vertique.kafka.KafkaSource;
                        import jakarta.inject.Inject;

                        public class OrderService {
                            @Inject
                            OrderService() {}

                            @KafkaSource(topic = "order-events")
                            public void onOrder(String payload) {}
                        }
                        """));

        result.assertSuccess();
        result.assertGeneratedSourceContains(GENERATED_MODULE_FQN, "userEventConsumerBinding");
        result.assertGeneratedSourceDoesNotContain(GENERATED_MODULE_FQN, "orderServiceBinding");
    }

    @Test
    @DisplayName("a package-private consumer outside the module package is skipped with a warning, not emitted")
    void packagePrivateConsumerOutsideModulePackage_isSkippedWithWarning() {
        // The two origins resolve the module to package "dev.vertique.examples", from which the
        // package-private consumer in a sibling package cannot be named.
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
                SourceFiles.inline("dev.vertique.examples.hidden.HiddenConsumer", """
                        package dev.vertique.examples.hidden;

                        import dev.vertique.kafka.KafkaListener;
                        import jakarta.inject.Inject;

                        @KafkaListener(topic = "hidden-events")
                        class HiddenConsumer {
                            @Inject
                            HiddenConsumer() {}
                        }
                        """));

        String module = "dev.vertique.examples.GeneratedKafkaConsumersModule";
        result.assertSuccess();
        result.assertWarningMessage("dev.vertique.examples.hidden.HiddenConsumer is not accessible from package");
        result.assertGeneratedSourceContains(module, "userEventConsumerBinding");
        result.assertGeneratedSourceDoesNotContain(module, "hiddenConsumerBinding");
    }

    @Test
    @DisplayName("a package-private consumer is still bound when the module lands in its own package")
    void packagePrivateConsumerInModulePackage_isBound() {
        var result = ProcessorTestHarness.run(
                new AutoWireProcessor(),
                SourceFiles.inline("dev.vertique.kafka.KafkaListener", KAFKA_LISTENER_ANNOTATION),
                SourceFiles.inline("dev.vertique.kafka.KafkaSource", KAFKA_SOURCE_ANNOTATION),
                SourceFiles.inline("dev.vertique.kafka.KafkaConsumers", KAFKA_CONSUMERS_QUALIFIER),
                SourceFiles.inline("dev.vertique.examples.hidden.HiddenConsumer", """
                        package dev.vertique.examples.hidden;

                        import dev.vertique.kafka.KafkaListener;
                        import jakarta.inject.Inject;

                        @KafkaListener(topic = "hidden-events")
                        class HiddenConsumer {
                            @Inject
                            HiddenConsumer() {}
                        }
                        """));

        result.assertSuccess();
        result.assertNoWarnings();
        result.assertGeneratedSourceContains(
                "dev.vertique.examples.hidden.GeneratedKafkaConsumersModule", "hiddenConsumerBinding");
    }

    @Test
    @DisplayName("no module is generated when every consumer is unreachable from the configured package")
    void everyConsumerUnreachable_generatesNoModule() {
        var result = ProcessorTestHarness.run(
                new AutoWireProcessor(),
                java.util.Map.of("vertique.codegen.package", "dev.vertique.examples.other"),
                SourceFiles.inline("dev.vertique.kafka.KafkaListener", KAFKA_LISTENER_ANNOTATION),
                SourceFiles.inline("dev.vertique.kafka.KafkaSource", KAFKA_SOURCE_ANNOTATION),
                SourceFiles.inline("dev.vertique.kafka.KafkaConsumers", KAFKA_CONSUMERS_QUALIFIER),
                SourceFiles.inline("dev.vertique.examples.hidden.HiddenConsumer", """
                        package dev.vertique.examples.hidden;

                        import dev.vertique.kafka.KafkaListener;
                        import jakarta.inject.Inject;

                        @KafkaListener(topic = "hidden-events")
                        class HiddenConsumer {
                            @Inject
                            HiddenConsumer() {}
                        }
                        """));

        result.assertSuccess();
        result.assertWarningMessage("dev.vertique.examples.hidden.HiddenConsumer is not accessible from package");
        org.junit.jupiter.api.Assertions.assertTrue(result.compilation()
                .generatedSourceFile("dev.vertique.examples.other.GeneratedKafkaConsumersModule")
                .isEmpty());
    }
}
