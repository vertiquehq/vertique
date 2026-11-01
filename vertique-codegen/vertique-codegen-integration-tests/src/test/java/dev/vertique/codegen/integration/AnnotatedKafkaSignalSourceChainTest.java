// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.integration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dagger.Binds;
import dagger.multibindings.IntoSet;
import dev.vertique.codegen.dagger.processor.AutoWireProcessor;
import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import dev.vertique.codegen.workflow.signal.KafkaSignalListenerProcessor;
import dev.vertique.workflow.kafka.KafkaSignalSource;
import dev.vertique.workflow.kafka.WorkflowKafkaSignalsModule;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import javax.annotation.processing.Processor;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Compiles an interface annotated {@code @KafkaSignalListener} through its processor and {@link AutoWireProcessor} in
 * one compilation, against the real annotations, {@code KafkaSignalSource} and transport module.
 *
 * <p>The generated {@code <Interface>_SignalSource} exists only from the second round, and the processor that registers
 * it emits in the first round, so the registration refers to the class by name before it exists. These cases hold the
 * real chain: a registration that did not resolve in the later round fails the compilation, and a compilation that already
 * failed reports its own errors alone.
 */
class AnnotatedKafkaSignalSourceChainTest {

    private static final String PACKAGE = "dev.vertique.examples";
    private static final String GENERATED_MODULE_FQN = PACKAGE + ".GeneratedRegistrationsModule";
    private static final String GENERATED_SOURCE_FQN = PACKAGE + ".CarrierSignals_SignalSource";

    private static final JavaFileObject CONTRACT = SourceFiles.inline(PACKAGE + ".OrdersContract", """
            package dev.vertique.examples;

            import dev.vertique.workflow.contract.SignalDedupKey;
            import dev.vertique.workflow.contract.WorkflowContract;
            import dev.vertique.workflow.contract.WorkflowSignal;
            import dev.vertique.workflow.ops.WorkflowInstanceId;
            import io.vertx.core.Future;

            @WorkflowContract("orders")
            public interface OrdersContract {
                @WorkflowSignal("shipment-delivered")
                Future<Void> shipmentDelivered(
                        WorkflowInstanceId instanceId, @SignalDedupKey String dedupKey, ShipmentDelivered payload);

                @WorkflowSignal("order-delivered")
                Future<Void> orderDelivered(
                        WorkflowInstanceId instanceId, @SignalDedupKey String dedupKey, ShipmentDelivered payload);
            }
            """);

    private static final JavaFileObject MESSAGE = SourceFiles.inline(PACKAGE + ".ShipmentDelivered", """
            package dev.vertique.examples;

            public record ShipmentDelivered(String eventId, String carrierRef, String orderNumber) {}
            """);

    /** States one target by key and one by business key, both with one dedup key. */
    private static final String LISTENER_TEMPLATE = """
            package dev.vertique.examples;

            import dev.vertique.workflow.kafka.KafkaSignalListener;
            import dev.vertique.workflow.source.WorkflowSignalTarget;

            @KafkaSignalListener(name = "carrier-events", topic = "carrier.events", groupId = "logistics")
            public interface CarrierSignals {
                @WorkflowSignalTarget(
                        contract = OrdersContract.class,
                        signal = "%s",
                        key = "carrierRef",
                        dedupBy = "eventId")
                @WorkflowSignalTarget(
                        contract = OrdersContract.class,
                        signal = "order-delivered",
                        businessKey = "orderNumber",
                        dedupBy = "eventId")
                void delivered(ShipmentDelivered event);
            }
            """;

    @Test
    @DisplayName("a generated source resolves through both processors")
    void generatedSourceResolvesThroughBothProcessors() {
        // Given a contract of two signals, a message record, and a @KafkaSignalListener interface CarrierSignals that
        // carries a key target and a business-key target on one method, both with dedupBy eventId, and no
        // @KafkaHandler
        JavaFileObject listener = listener("shipment-delivered");

        // When the signal listener processor and AutoWireProcessor compile them in one compilation
        var result = compileChain(listener);

        // Then compilation succeeds
        result.assertSuccess();
        // And CarrierSignals_SignalSource is generated, loads, and is a KafkaSignalSource
        Class<?> source = result.loadGeneratedClass(GENERATED_SOURCE_FQN);
        assertTrue(KafkaSignalSource.class.isAssignableFrom(source), "the generated class is a KafkaSignalSource");
        // And the generated registrations module has a @Binds @IntoSet method that takes it and returns
        // KafkaSignalSource<?>, and its @Module includes WorkflowKafkaSignalsModule
        Class<?> module = result.loadGeneratedClass(GENERATED_MODULE_FQN);
        List<Method> bindings = Arrays.stream(module.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Binds.class))
                .toList();
        assertEquals(1, bindings.size(), "one binding, for the one annotated listener");
        Method binding = bindings.get(0);
        assertTrue(Modifier.isAbstract(binding.getModifiers()), "the binding is abstract");
        assertTrue(binding.isAnnotationPresent(IntoSet.class), "the binding contributes into a Set");
        assertEquals(
                KafkaSignalSource.class.getName() + "<?>",
                binding.getGenericReturnType().getTypeName());
        assertArrayEquals(new Class<?>[] {source}, binding.getParameterTypes());
        assertArrayEquals(
                new Class<?>[] {WorkflowKafkaSignalsModule.class},
                module.getAnnotation(dagger.Module.class).includes());
        // And no CarrierSignals_BindingMeta is generated
        assertTrue(
                result.compilation()
                        .generatedSourceFile(PACKAGE + ".CarrierSignals_BindingMeta")
                        .isEmpty(),
                "a signal listener emits no binding meta");
        // And no note asks for a manual @Provides for CarrierSignals
        List<String> notesAboutTheListener = result.compilation().notes().stream()
                .map(note -> note.getMessage(null))
                .filter(message -> message.contains("CarrierSignals"))
                .toList();
        assertEquals(List.of(), notesAboutTheListener);
    }

    @Test
    @DisplayName("a misspelled signal names the signal and no missing processor")
    void misspelledSignalNamesTheSignalAndNoMissingProcessor() {
        // Given the same fixtures with the key target's signal written shipment-delivred, which the contract does not
        // declare
        JavaFileObject listener = listener("shipment-delivred");

        // When the signal listener processor and AutoWireProcessor compile them in one compilation
        var result = compileChain(listener);

        // Then compilation fails with an error on the listener whose message contains the misspelled signal
        result.assertFailed();
        List<Diagnostic<? extends JavaFileObject>> errors = result.compilation().errors();
        assertTrue(
                errors.stream()
                        .anyMatch(error -> error.getSource() != null
                                && error.getSource().getName().endsWith("/CarrierSignals.java")
                                && error.getMessage(null).contains("shipment-delivred")),
                () -> "an error on the listener must name the signal but the errors were " + messages(errors));
        // And no diagnostic names a processor to add; javac may still report the missing generated class
        List<String> naming = result.compilation().diagnostics().stream()
                .map(diagnostic -> diagnostic.getMessage(null))
                .filter(message -> message.contains("annotation processor that handles"))
                .toList();
        assertEquals(List.of(), naming, "no diagnostic may name the processor to add");
    }

    private static JavaFileObject listener(String keyTargetSignal) {
        return SourceFiles.inline(PACKAGE + ".CarrierSignals", LISTENER_TEMPLATE.formatted(keyTargetSignal));
    }

    private static ProcessorTestHarness.Result compileChain(JavaFileObject listener) {
        List<Processor> processors = List.of(new KafkaSignalListenerProcessor(), new AutoWireProcessor());
        return ProcessorTestHarness.run(processors, CONTRACT, MESSAGE, listener);
    }

    private static List<String> messages(List<Diagnostic<? extends JavaFileObject>> diagnostics) {
        return diagnostics.stream()
                .map(diagnostic -> diagnostic.getMessage(null))
                .toList();
    }
}
