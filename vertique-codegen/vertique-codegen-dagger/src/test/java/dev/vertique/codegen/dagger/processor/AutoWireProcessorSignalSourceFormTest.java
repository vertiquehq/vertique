// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.dagger.processor;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dagger.Binds;
import dagger.multibindings.IntoSet;
import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests that {@link AutoWireProcessor} binds, in the round in which it sees the interface, the class that another
 * processor generates for it, and reports a class that no processor generated.
 *
 * <p>An interface annotated with an annotation that carries {@code @SignalSourceForm} has its
 * {@code <Interface>_SignalSource} bound by name into the {@code Set} of the form's interface, whose
 * {@code @SignalSourceType} names the transport module the generated registrations module includes. A
 * {@code @KafkaListener} interface with signal targets is bound the same way into the {@code Set} of the Kafka source
 * type. The class is first visible in a later round, which the processor never sees in these runs because the generating
 * processor is absent: the source set either holds the class, as a later round would make it, or leaves it out. The
 * processor identifies every annotation and interface by fully qualified name, so all of them are inline stubs and this
 * module takes no dependency on the workflow modules.
 */
class AutoWireProcessorSignalSourceFormTest {

    private static final String PACKAGE = "dev.vertique.examples";
    private static final String GENERATED_MODULE_FQN = PACKAGE + ".GeneratedRegistrationsModule";

    private static final String SIGNAL_SOURCE_TYPE = """
            package dev.vertique.workflow.source;

            import java.lang.annotation.*;

            @Target(ElementType.TYPE)
            @Retention(RetentionPolicy.CLASS)
            public @interface SignalSourceType {
                Class<?> module();
            }
            """;

    private static final String FORM = """
            package dev.vertique.workflow.source;

            import java.lang.annotation.*;

            @Target(ElementType.ANNOTATION_TYPE)
            @Retention(RetentionPolicy.CLASS)
            public @interface SignalSourceForm {
                Class<?> value();
            }
            """;

    private static final String SIGNAL_NO_MATCH = """
            package dev.vertique.workflow.source;

            public enum SignalNoMatch { IGNORE, RETRY }
            """;

    private static final String SIGNAL_TARGET = """
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
            """;

    private static final String SIGNAL_TARGETS = """
            package dev.vertique.workflow.source;

            import java.lang.annotation.*;

            @Target(ElementType.METHOD)
            @Retention(RetentionPolicy.CLASS)
            public @interface WorkflowSignalTargets {
                WorkflowSignalTarget[] value();
            }
            """;

    private static final String KAFKA_LISTENER = """
            package dev.vertique.kafka;

            import java.lang.annotation.*;

            @Target(ElementType.TYPE)
            @Retention(RetentionPolicy.RUNTIME)
            public @interface KafkaListener {
                String name() default "";
                String topic() default "";
                String groupId() default "";
            }
            """;

    private static final String KAFKA_SIGNALS_MODULE = """
            package dev.vertique.workflow.kafka;

            import dagger.Module;

            @Module
            public abstract class WorkflowKafkaSignalsModule {}
            """;

    private static final String KAFKA_SIGNAL_SOURCE = """
            package dev.vertique.workflow.kafka;

            import dev.vertique.workflow.source.SignalSourceType;

            @SignalSourceType(module = WorkflowKafkaSignalsModule.class)
            public interface KafkaSignalSource<T> {}
            """;

    private static final String FEED_MODULE = """
            package dev.vertique.examples;

            import dagger.Module;

            @Module
            public abstract class FeedModule {}
            """;

    private static final String FEED = """
            package dev.vertique.examples;

            import dev.vertique.workflow.source.SignalSourceType;

            @SignalSourceType(module = FeedModule.class)
            public interface Feed<T> {}
            """;

    private static final String FEED_SIGNALS = """
            package dev.vertique.examples;

            import dev.vertique.workflow.source.SignalSourceForm;
            import java.lang.annotation.*;

            @SignalSourceForm(Feed.class)
            @Target(ElementType.TYPE)
            @Retention(RetentionPolicy.CLASS)
            public @interface FeedSignals {}
            """;

    private static final String PLAIN_SIGNALS = """
            package dev.vertique.examples;

            import java.lang.annotation.*;

            @Target(ElementType.TYPE)
            @Retention(RetentionPolicy.CLASS)
            public @interface PlainSignals {}
            """;

    private static final String SHIPMENT = """
            package dev.vertique.examples;

            public record Shipment(String carrierRef) {}
            """;

    private static final String ORDERS_CONTRACT = """
            package dev.vertique.examples;

            public interface OrdersContract {}
            """;

    private static final String CARRIER_FEED = """
            package dev.vertique.examples;

            @FeedSignals
            public interface CarrierFeed {}
            """;

    private static final String CARRIER_FEED_SOURCE = """
            package dev.vertique.examples;

            import jakarta.inject.Inject;

            public final class CarrierFeed_SignalSource implements Feed<Shipment> {
                @Inject
                public CarrierFeed_SignalSource() {}
            }
            """;

    private static final String PLAIN_FEED = """
            package dev.vertique.examples;

            @PlainSignals
            public interface PlainFeed {}
            """;

    private static final String PLAIN_FEED_SOURCE = """
            package dev.vertique.examples;

            import jakarta.inject.Inject;

            public final class PlainFeed_SignalSource implements Feed<Shipment> {
                @Inject
                public PlainFeed_SignalSource() {}
            }
            """;

    private static final String CARRIER_SIGNALS = """
            package dev.vertique.examples;

            import dev.vertique.kafka.KafkaListener;
            import dev.vertique.workflow.source.WorkflowSignalTarget;

            @KafkaListener(name = "carrier-events", topic = "carrier.events", groupId = "logistics")
            public interface CarrierSignals {
                @WorkflowSignalTarget(contract = OrdersContract.class, signal = "shipment-delivered", key = "carrierRef")
                void delivered(Shipment event);
            }
            """;

    private static final String CARRIER_SIGNALS_SOURCE = """
            package dev.vertique.examples;

            import dev.vertique.workflow.kafka.KafkaSignalSource;
            import jakarta.inject.Inject;

            public final class CarrierSignals_SignalSource implements KafkaSignalSource<Shipment> {
                @Inject
                public CarrierSignals_SignalSource() {}
            }
            """;

    @Test
    @DisplayName("a form-annotated interface is bound by name into its form's set")
    void formAnnotatedInterfaceIsBoundByNameIntoItsFormsSet() throws Exception {
        // Given a marked generic interface Feed with a module, an annotation @FeedSignals that carries
        // @SignalSourceForm(Feed.class), an interface CarrierFeed annotated with it, and a class
        // CarrierFeed_SignalSource with an @Inject constructor that is present as a source, as a later round would make
        // it; and a second interface annotated with an annotation that carries no form, with its own class
        // When the processor compiles them
        var result = compile(
                source("CarrierFeed", CARRIER_FEED),
                source("CarrierFeed_SignalSource", CARRIER_FEED_SOURCE),
                source("PlainFeed", PLAIN_FEED),
                source("PlainFeed_SignalSource", PLAIN_FEED_SOURCE));

        // Then compilation succeeds and the generated registrations module has an abstract @Binds @IntoSet method that
        // takes CarrierFeed_SignalSource and returns Feed<?>, its @Module includes FeedModule, and the interface of the
        // annotation with no form contributes no binding
        result.assertSuccess();
        Class<?> module = result.loadGeneratedClass(GENERATED_MODULE_FQN);
        Class<?> carrierSource = result.loadGeneratedClass(PACKAGE + ".CarrierFeed_SignalSource");
        Class<?> feedModule = result.loadGeneratedClass(PACKAGE + ".FeedModule");
        List<Method> bindings = Arrays.stream(module.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Binds.class))
                .toList();
        assertEquals(1, bindings.size(), "one binding, for the one form-annotated interface");
        Method binding = bindings.get(0);
        assertTrue(Modifier.isAbstract(binding.getModifiers()), "the binding is abstract");
        assertTrue(binding.isAnnotationPresent(IntoSet.class), "the binding contributes into a Set");
        assertEquals(PACKAGE + ".Feed<?>", binding.getGenericReturnType().getTypeName());
        assertArrayEquals(new Class<?>[] {carrierSource}, binding.getParameterTypes());
        assertArrayEquals(
                new Class<?>[] {feedModule},
                module.getAnnotation(dagger.Module.class).includes());
    }

    @Test
    @DisplayName("a predicted source that was never generated is reported on its interface naming the processor")
    void predictedSourceThatWasNeverGeneratedIsReportedOnItsInterfaceNamingTheProcessor() {
        // Given a @KafkaListener interface CarrierSignals with a signal target, and a form-annotated interface
        // CarrierFeed, and no class CarrierSignals_SignalSource or CarrierFeed_SignalSource among the sources; and for
        // a
        // control, the same sources plus a hand-written class of each name
        JavaFileObject carrierSignals = source("CarrierSignals", CARRIER_SIGNALS);
        JavaFileObject carrierFeed = source("CarrierFeed", CARRIER_FEED);

        // When the processor compiles each set, with the Kafka processor absent
        var missing = compile(carrierSignals, carrierFeed);
        var control = compile(
                carrierSignals,
                carrierFeed,
                source("CarrierSignals_SignalSource", CARRIER_SIGNALS_SOURCE),
                source("CarrierFeed_SignalSource", CARRIER_FEED_SOURCE));

        // Then the first compilation fails with one error on CarrierSignals that names CarrierSignals_SignalSource and
        // vertique-codegen-kafka, and one on CarrierFeed that names CarrierFeed_SignalSource
        missing.assertFailed();
        List<String> onListener = errorsIn(missing, "CarrierSignals.java");
        assertEquals(1, onListener.size(), () -> "one error on CarrierSignals but were " + onListener);
        assertTrue(
                onListener.get(0).contains(PACKAGE + ".CarrierSignals_SignalSource")
                        && onListener.get(0).contains("vertique-codegen-kafka"),
                () -> "the error must name the class and the processor but was " + onListener);
        List<String> onForm = errorsIn(missing, "CarrierFeed.java");
        assertEquals(1, onForm.size(), () -> "one error on CarrierFeed but were " + onForm);
        assertTrue(
                onForm.get(0).contains(PACKAGE + ".CarrierFeed_SignalSource"),
                () -> "the error must name the class but was " + onForm);
        // And the control compiles with neither error
        control.assertSuccess();
        assertEquals(List.of(), errorsIn(control, "CarrierSignals.java"));
        assertEquals(List.of(), errorsIn(control, "CarrierFeed.java"));
    }

    /** The messages of the errors reported on the source file, which is where the processor puts an error on an element. */
    private static List<String> errorsIn(ProcessorTestHarness.Result result, String fileName) {
        return result.compilation().diagnostics().stream()
                .filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)
                .filter(diagnostic -> diagnostic.getSource() != null
                        && diagnostic.getSource().getName().endsWith("/" + fileName))
                .map(diagnostic -> diagnostic.getMessage(null))
                .toList();
    }

    private static JavaFileObject source(String simpleName, String body) {
        return SourceFiles.inline(PACKAGE + "." + simpleName, body);
    }

    /** Compiles the sources with the stubs of the annotations, the transport and the form's interface. */
    private static ProcessorTestHarness.Result compile(JavaFileObject... sources) {
        return ProcessorTestHarness.run(
                new AutoWireProcessor(),
                Stream.concat(
                                Stream.of(
                                        SourceFiles.inline(
                                                "dev.vertique.workflow.source.SignalSourceType", SIGNAL_SOURCE_TYPE),
                                        SourceFiles.inline("dev.vertique.workflow.source.SignalSourceForm", FORM),
                                        SourceFiles.inline(
                                                "dev.vertique.workflow.source.SignalNoMatch", SIGNAL_NO_MATCH),
                                        SourceFiles.inline(
                                                "dev.vertique.workflow.source.WorkflowSignalTarget", SIGNAL_TARGET),
                                        SourceFiles.inline(
                                                "dev.vertique.workflow.source.WorkflowSignalTargets", SIGNAL_TARGETS),
                                        SourceFiles.inline("dev.vertique.kafka.KafkaListener", KAFKA_LISTENER),
                                        SourceFiles.inline(
                                                "dev.vertique.workflow.kafka.WorkflowKafkaSignalsModule",
                                                KAFKA_SIGNALS_MODULE),
                                        SourceFiles.inline(
                                                "dev.vertique.workflow.kafka.KafkaSignalSource", KAFKA_SIGNAL_SOURCE),
                                        source("FeedModule", FEED_MODULE),
                                        source("Feed", FEED),
                                        source("FeedSignals", FEED_SIGNALS),
                                        source("PlainSignals", PLAIN_SIGNALS),
                                        source("Shipment", SHIPMENT),
                                        source("OrdersContract", ORDERS_CONTRACT)),
                                Arrays.stream(sources))
                        .toArray(JavaFileObject[]::new));
    }
}
