// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.workflow.signal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import java.util.List;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link SignalTargetHostProcessor} reports, on its type, a type that declares a signal target and that no
 * processor turns into a source, and stays silent for a type some processor handles: one annotated with an annotation
 * that carries {@code @SignalSourceForm}, and one annotated {@code @KafkaListener}, which {@link
 * KafkaSignalListenerProcessor} reports. The annotations are inline stubs, because the processor identifies them by fully
 * qualified name.
 */
class SignalTargetHostProcessorTest {

    private static final String PACKAGE = "dev.vertique.examples";

    private static final JavaFileObject SIGNAL_NO_MATCH =
            SourceFiles.inline("dev.vertique.workflow.source.SignalNoMatch", """
            package dev.vertique.workflow.source;

            public enum SignalNoMatch { IGNORE, RETRY }
            """);

    private static final JavaFileObject SIGNAL_TARGET =
            SourceFiles.inline("dev.vertique.workflow.source.WorkflowSignalTarget", """
            package dev.vertique.workflow.source;

            import java.lang.annotation.*;

            @Target(ElementType.METHOD)
            @Retention(RetentionPolicy.CLASS)
            @Repeatable(WorkflowSignalTargets.class)
            public @interface WorkflowSignalTarget {
                Class<?> contract();
                String signal();
                String key() default "";
                String businessKey() default "";
                String dedupBy() default "";
                SignalNoMatch onNoMatch() default SignalNoMatch.IGNORE;
            }
            """);

    private static final JavaFileObject SIGNAL_TARGETS =
            SourceFiles.inline("dev.vertique.workflow.source.WorkflowSignalTargets", """
            package dev.vertique.workflow.source;

            import java.lang.annotation.*;

            @Target(ElementType.METHOD)
            @Retention(RetentionPolicy.CLASS)
            public @interface WorkflowSignalTargets {
                WorkflowSignalTarget[] value();
            }
            """);

    private static final JavaFileObject FORM = SourceFiles.inline("dev.vertique.workflow.source.SignalSourceForm", """
            package dev.vertique.workflow.source;

            import java.lang.annotation.*;

            @Target(ElementType.ANNOTATION_TYPE)
            @Retention(RetentionPolicy.CLASS)
            public @interface SignalSourceForm {
                Class<?> value();
            }
            """);

    private static final JavaFileObject KAFKA_LISTENER = SourceFiles.inline("dev.vertique.kafka.KafkaListener", """
            package dev.vertique.kafka;

            import java.lang.annotation.*;

            @Target(ElementType.TYPE)
            @Retention(RetentionPolicy.RUNTIME)
            public @interface KafkaListener {}
            """);

    private static final JavaFileObject FEED = source("Feed", """
            package dev.vertique.examples;

            public interface Feed<T> {}
            """);

    private static final JavaFileObject FEED_SIGNALS = source("FeedSignals", """
            package dev.vertique.examples;

            import dev.vertique.workflow.source.SignalSourceForm;
            import java.lang.annotation.*;

            @SignalSourceForm(Feed.class)
            @Target(ElementType.TYPE)
            @Retention(RetentionPolicy.CLASS)
            public @interface FeedSignals {}
            """);

    private static final JavaFileObject PLAIN_SIGNALS = source("PlainSignals", """
            package dev.vertique.examples;

            import java.lang.annotation.*;

            @Target(ElementType.TYPE)
            @Retention(RetentionPolicy.CLASS)
            public @interface PlainSignals {}
            """);

    private static final String METHOD = """
            @WorkflowSignalTarget(contract = Object.class, signal = "shipment-delivered", key = "carrierRef")
            void delivered(Object event);
            """;

    @Test
    @DisplayName("a target on a type that no processor turns into a source is an error naming the type")
    void targetOnATypeThatIsNoSourceIsAnErrorNamingTheType() {
        // Given a plain class, and an interface annotated with an annotation that carries no form, each with a target
        JavaFileObject plainClass = source("PlainClass", """
                package dev.vertique.examples;

                import dev.vertique.workflow.source.WorkflowSignalTarget;

                public class PlainClass {
                    @WorkflowSignalTarget(contract = Object.class, signal = "shipment-delivered", key = "carrierRef")
                    public void delivered(Object event) {}
                }
                """);
        JavaFileObject noSource = interfaceWith("NoSource", "@PlainSignals");

        // When the processor compiles them
        var result = compile(plainClass, noSource);

        // Then each type is reported on itself, naming it and @SignalSourceForm
        result.assertFailed();
        assertTrue(errorsIn(result, "PlainClass").stream()
                .anyMatch(m -> m.contains(PACKAGE + ".PlainClass") && m.contains("@SignalSourceForm")));
        assertTrue(errorsIn(result, "NoSource").stream().anyMatch(m -> m.contains(PACKAGE + ".NoSource")));
    }

    @Test
    @DisplayName("a type some processor handles draws no error from this processor")
    void typeSomeProcessorHandlesDrawsNoError() {
        // Given an interface annotated with an annotation that carries @SignalSourceForm, and an interface annotated
        // @KafkaListener, each with a target
        JavaFileObject form = interfaceWith("CarrierFeed", "@FeedSignals");
        JavaFileObject listener = interfaceWith("CarrierListener", "@dev.vertique.kafka.KafkaListener");

        // When the processor compiles them
        var result = compile(form, listener);

        // Then compilation succeeds: the first is the form processor's, the second the Kafka processor's
        result.assertSuccess();
        assertEquals(List.of(), errorsIn(result, "CarrierFeed"));
        assertEquals(List.of(), errorsIn(result, "CarrierListener"));
    }

    private static List<String> errorsIn(ProcessorTestHarness.Result result, String typeName) {
        return result.compilation().errors().stream()
                .filter(d -> d.getSource() != null && d.getSource().getName().endsWith("/" + typeName + ".java"))
                .map(d -> d.getMessage(null))
                .toList();
    }

    private static JavaFileObject interfaceWith(String name, String annotation) {
        return source(name, """
                package dev.vertique.examples;

                import dev.vertique.workflow.source.WorkflowSignalTarget;

                %s
                public interface %s {
                %s
                }
                """.formatted(annotation, name, METHOD));
    }

    private static JavaFileObject source(String simpleName, String body) {
        return SourceFiles.inline(PACKAGE + "." + simpleName, body);
    }

    private static ProcessorTestHarness.Result compile(JavaFileObject... sources) {
        JavaFileObject[] fixed = {
            SIGNAL_NO_MATCH, SIGNAL_TARGET, SIGNAL_TARGETS, FORM, KAFKA_LISTENER, FEED, FEED_SIGNALS, PLAIN_SIGNALS
        };
        JavaFileObject[] all = new JavaFileObject[fixed.length + sources.length];
        System.arraycopy(fixed, 0, all, 0, fixed.length);
        System.arraycopy(sources, 0, all, fixed.length, sources.length);
        return ProcessorTestHarness.run(new SignalTargetHostProcessor(), all);
    }
}
