// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.dagger.processor;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests how {@link AutoWireProcessor} resolves the generated module's package when the auto-wired
 * types are declared in the unnamed package.
 */
class AutoWireProcessorUnnamedPackageTest {

    private static final String KAFKA_LISTENER_ANNOTATION = """
            package dev.vertique.kafka;

            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;

            @Target(ElementType.TYPE)
            @Retention(RetentionPolicy.RUNTIME)
            public @interface KafkaListener {
                String topic() default "";
            }
            """;

    private static final String KAFKA_CONSUMERS_QUALIFIER = """
            package dev.vertique.kafka;

            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;
            import jakarta.inject.Qualifier;

            @Qualifier
            @Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD})
            @Retention(RetentionPolicy.RUNTIME)
            public @interface KafkaConsumers {}
            """;

    private static String consumer(String packageLine, String name) {
        return packageLine + """

                import dev.vertique.kafka.KafkaListener;
                import jakarta.inject.Inject;

                @KafkaListener(topic = "events")
                public class %s {
                    @Inject
                    public %s() {}
                }
                """.formatted(name, name);
    }

    @Test
    @DisplayName("a single auto-wired class in the unnamed package yields a module in the unnamed package")
    void singleOriginInUnnamedPackage_generatesModuleThere() {
        var result = ProcessorTestHarness.run(
                new AutoWireProcessor(),
                SourceFiles.inline("dev.vertique.kafka.KafkaListener", KAFKA_LISTENER_ANNOTATION),
                SourceFiles.inline("dev.vertique.kafka.KafkaConsumers", KAFKA_CONSUMERS_QUALIFIER),
                SourceFiles.inline("RootConsumer", consumer("", "RootConsumer")));

        result.assertSuccess();
        result.assertGeneratedSourceContains("GeneratedKafkaConsumersModule", "rootConsumerBinding");
        result.assertGeneratedSourceDoesNotContain("GeneratedKafkaConsumersModule", "package ");
    }

    @Test
    @DisplayName("several auto-wired classes all in the unnamed package yield a module in the unnamed package")
    void severalOriginsInUnnamedPackage_generateModuleThere() {
        var result = ProcessorTestHarness.run(
                new AutoWireProcessor(),
                SourceFiles.inline("dev.vertique.kafka.KafkaListener", KAFKA_LISTENER_ANNOTATION),
                SourceFiles.inline("dev.vertique.kafka.KafkaConsumers", KAFKA_CONSUMERS_QUALIFIER),
                SourceFiles.inline("FirstConsumer", consumer("", "FirstConsumer")),
                SourceFiles.inline("SecondConsumer", consumer("", "SecondConsumer")));

        result.assertSuccess();
        result.assertGeneratedSourceContains("GeneratedKafkaConsumersModule", "firstConsumerBinding");
        result.assertGeneratedSourceContains("GeneratedKafkaConsumersModule", "secondConsumerBinding");
    }

    @Test
    @DisplayName("origins split between the unnamed package and a named package still report the disjoint error")
    void unnamedAndNamedOrigins_reportDisjointPackages() {
        var result = ProcessorTestHarness.run(
                new AutoWireProcessor(),
                SourceFiles.inline("dev.vertique.kafka.KafkaListener", KAFKA_LISTENER_ANNOTATION),
                SourceFiles.inline("dev.vertique.kafka.KafkaConsumers", KAFKA_CONSUMERS_QUALIFIER),
                SourceFiles.inline("RootConsumer", consumer("", "RootConsumer")),
                SourceFiles.inline(
                        "dev.vertique.examples.NamedConsumer",
                        consumer("package dev.vertique.examples;\n", "NamedConsumer")));

        result.assertFailed().assertErrorMessage("span disjoint packages");
    }
}
