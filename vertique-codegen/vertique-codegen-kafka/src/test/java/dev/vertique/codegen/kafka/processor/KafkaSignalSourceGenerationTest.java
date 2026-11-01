// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.kafka.processor;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Verifies the compile errors {@link KafkaConsumerProcessor} reports for a {@code @KafkaListener} interface whose
 * methods carry {@code @WorkflowSignalTarget}: a signal the contract does not declare, an accessor the message type
 * lacks, a message type that is not the signal's, a business-key target with no dedup key, targets that disagree on the
 * dedup key, strategies and settings the generated source cannot honour, and shapes it cannot honour.
 *
 * <p>Every case here is a compile failure, so the workflow annotations are inline stubs that the processor identifies by
 * fully qualified name; the cases that must generate need the real types and live in the integration-test module. The
 * Kafka annotations are the real ones on this module's test class path. Each failing case is asserted to name the
 * offending interface together with the member or setting that is wrong, so that a rejection for another reason does not
 * satisfy it.
 */
class KafkaSignalSourceGenerationTest {

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

    private static final JavaFileObject WORKFLOW_CONTRACT =
            SourceFiles.inline("dev.vertique.workflow.contract.WorkflowContract", """
            package dev.vertique.workflow.contract;

            import java.lang.annotation.*;

            @Target(ElementType.TYPE)
            @Retention(RetentionPolicy.RUNTIME)
            public @interface WorkflowContract {
                String value() default "";
            }
            """);

    private static final JavaFileObject WORKFLOW_SIGNAL =
            SourceFiles.inline("dev.vertique.workflow.contract.WorkflowSignal", """
            package dev.vertique.workflow.contract;

            import java.lang.annotation.*;

            @Target(ElementType.METHOD)
            @Retention(RetentionPolicy.RUNTIME)
            public @interface WorkflowSignal {
                String value();
            }
            """);

    private static final JavaFileObject SIGNAL_DEDUP_KEY =
            SourceFiles.inline("dev.vertique.workflow.contract.SignalDedupKey", """
            package dev.vertique.workflow.contract;

            import java.lang.annotation.*;

            @Target(ElementType.PARAMETER)
            @Retention(RetentionPolicy.RUNTIME)
            public @interface SignalDedupKey {}
            """);

    private static final JavaFileObject WORKFLOW_INSTANCE_ID =
            SourceFiles.inline("dev.vertique.workflow.ops.WorkflowInstanceId", """
            package dev.vertique.workflow.ops;

            public record WorkflowInstanceId(java.util.UUID value) {}
            """);

    private static final JavaFileObject SHIPMENT_DELIVERED = SourceFiles.inline(PACKAGE + ".ShipmentDelivered", """
            package dev.vertique.examples;

            public record ShipmentDelivered(String eventId, String carrierRef, String orderNumber) {}
            """);

    private static final JavaFileObject SHIPMENT_CANCELLED = SourceFiles.inline(PACKAGE + ".ShipmentCancelled", """
            package dev.vertique.examples;

            public record ShipmentCancelled(String eventId, String carrierRef) {}
            """);

    private static final JavaFileObject DELIVERY_CONFIRMED = SourceFiles.inline(PACKAGE + ".DeliveryConfirmed", """
            package dev.vertique.examples;

            public record DeliveryConfirmed(String confirmedAt) {}
            """);

    /** A contract whose signals take the message records, so that a target on them has a payload of the same type. */
    private static final JavaFileObject ORDERS_CONTRACT = SourceFiles.inline(PACKAGE + ".OrdersContract", """
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

                @WorkflowSignal("parcel-delivered")
                Future<Void> parcelDelivered(
                        WorkflowInstanceId instanceId, @SignalDedupKey String dedupKey, ShipmentDelivered payload);

                @WorkflowSignal("shipment-cancelled")
                Future<Void> shipmentCancelled(
                        WorkflowInstanceId instanceId, @SignalDedupKey String dedupKey, ShipmentCancelled payload);
            }
            """);

    /** A contract whose {@code shipment-delivered} takes {@code DeliveryConfirmed}, not the message of the listener. */
    private static final JavaFileObject CONFIRMATION_CONTRACT =
            SourceFiles.inline(PACKAGE + ".ConfirmationContract", """
            package dev.vertique.examples;

            import dev.vertique.workflow.contract.SignalDedupKey;
            import dev.vertique.workflow.contract.WorkflowContract;
            import dev.vertique.workflow.contract.WorkflowSignal;
            import dev.vertique.workflow.ops.WorkflowInstanceId;
            import io.vertx.core.Future;

            @WorkflowContract("confirmations")
            public interface ConfirmationContract {
                @WorkflowSignal("shipment-delivered")
                Future<Void> shipmentDelivered(
                        WorkflowInstanceId instanceId, @SignalDedupKey String dedupKey, DeliveryConfirmed payload);
            }
            """);

    private static final String DELIVERED = "signal = \"shipment-delivered\"";
    private static final String PARCEL = "signal = \"parcel-delivered\"";
    private static final String CANCELLED = "signal = \"shipment-cancelled\"";

    // --- the cases that name the signal, the accessor, the types and the dedup key ---

    @Test
    @DisplayName("a signal the contract does not declare fails compilation naming the signal")
    void misspelledSignalFailsCompilationNamingTheSignal() {
        // Given a contract that declares shipment-delivered and a listener whose target names shipment-delivred
        JavaFileObject listener = listener("", "", """
                @WorkflowSignalTarget(contract = OrdersContract.class, signal = "shipment-delivred", key = "carrierRef")
                void carrierDelivery(ShipmentDelivered message);
                """);

        // When the processor compiles them
        var result = compile(listener);

        // Then compilation fails and an error contains the misspelled name
        assertErrorNaming(result, "shipment-delivred");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("misspelledAccessors")
    @DisplayName("a key, business key or dedup accessor the message type lacks fails compilation naming it")
    void misspelledAccessorFailsCompilationNamingIt(String attribute, String targetAttributes) {
        // Given a message type with the accessor carrierRef only, and a target that writes carrierRefs in one attribute
        JavaFileObject listener = listener("", "", """
                @WorkflowSignalTarget(contract = OrdersContract.class, %s, %s)
                void carrierDelivery(ShipmentDelivered message);
                """.formatted(DELIVERED, targetAttributes));

        // When the processor compiles it
        var result = compile(listener);

        // Then compilation fails and an error contains carrierRefs
        assertErrorNaming(result, "carrierRefs");
    }

    static Stream<Arguments> misspelledAccessors() {
        return Stream.of(
                Arguments.of("key", "key = \"carrierRefs\""),
                Arguments.of("businessKey", "businessKey = \"carrierRefs\", dedupBy = \"eventId\""),
                Arguments.of("dedupBy", "key = \"carrierRef\", dedupBy = \"carrierRefs\""));
    }

    @Test
    @DisplayName("a message type that differs from the signal's type fails compilation naming both types")
    void messageTypeDifferingFromSignalTypeFailsNamingBothTypes() {
        // Given a signal shipment-delivered whose payload is DeliveryConfirmed, and a listener method that takes
        // ShipmentDelivered, with a valid key and no way to map a payload
        JavaFileObject listener = listener("", "", """
                @WorkflowSignalTarget(contract = ConfirmationContract.class, %s, key = "carrierRef")
                void carrierDelivery(ShipmentDelivered message);
                """.formatted(DELIVERED));

        // When the processor compiles it
        var result = compile(listener);

        // Then compilation fails and one error contains both type names
        assertErrorNaming(result, "ShipmentDelivered", "DeliveryConfirmed");
    }

    @Test
    @DisplayName("a business-key target with no dedup key fails compilation naming the source")
    void businessKeyTargetWithoutDedupByFailsNamingTheSource() {
        // Given a listener CarrierSignals with a target that sets businessKey and no dedupBy
        JavaFileObject listener = listener("", "", """
                @WorkflowSignalTarget(contract = OrdersContract.class, %s, businessKey = "orderNumber")
                void carrierDelivery(ShipmentDelivered message);
                """.formatted(DELIVERED));

        // When the processor compiles it
        var result = compile(listener);

        // Then compilation fails and an error names the interface, its method and the missing dedupBy
        assertErrorNaming(result, "CarrierSignals", "carrierDelivery", "dedupBy");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("disagreeingTargets")
    @DisplayName("targets that disagree on the dedup key fail compilation naming the listener")
    void targetsDisagreeingOnDedupByFailCompilationNamingTheListener(String caseName, String targets) {
        // Given a listener with two targets on one method that state different dedup keys
        JavaFileObject listener = listener("", "", targets + "\nvoid carrierDelivery(ShipmentDelivered message);\n");

        // When the processor compiles it
        var result = compile(listener);

        // Then compilation fails and an error names CarrierSignals and dedupBy
        assertErrorNaming(result, "CarrierSignals", "dedupBy");
    }

    static Stream<Arguments> disagreeingTargets() {
        return Stream.of(
                Arguments.of("two different dedup keys", """
                        @WorkflowSignalTarget(contract = OrdersContract.class, %s, key = "carrierRef", dedupBy = "eventId")
                        @WorkflowSignalTarget(contract = OrdersContract.class, %s, key = "carrierRef", dedupBy = "carrierRef")
                        """.formatted(DELIVERED, PARCEL)),
                Arguments.of("one dedup key and none", """
                        @WorkflowSignalTarget(contract = OrdersContract.class, %s, key = "carrierRef", dedupBy = "eventId")
                        @WorkflowSignalTarget(contract = OrdersContract.class, %s, key = "carrierRef")
                        """.formatted(DELIVERED, PARCEL)));
    }

    // --- the strategies and the settings the generated binding forces or takes none of ---

    @ParameterizedTest(name = "{0}")
    @MethodSource("otherStrategies")
    @DisplayName("a listener that writes other strategies fails compilation naming the attribute")
    void listenerSettingOtherStrategiesFailsCompilation(String attribute, String written) {
        // Given a valid listener with one target that also writes a strategy other than MANUAL or RETRY explicitly
        JavaFileObject listener = listener("", ", " + written, validTarget());

        // When the processor compiles it
        var result = compile(listener);

        // Then compilation fails and an error names CarrierSignals and the attribute
        assertErrorNaming(result, "CarrierSignals", attribute);
    }

    static Stream<Arguments> otherStrategies() {
        return Stream.of(
                Arguments.of("commitStrategy", "commitStrategy = CommitStrategy.AUTO"),
                Arguments.of("errorStrategy", "errorStrategy = ErrorStrategy.SKIP"),
                Arguments.of("errorStrategy", "errorStrategy = ErrorStrategy.DEAD_LETTER"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("settingsTheBindingWouldDrop")
    @DisplayName("a listener that writes a setting the generated binding would drop fails compilation naming it")
    void listenerSettingTheGeneratedBindingWouldDropFailsCompilationNamingIt(
            String setting, String typeAnnotations, String listenerAttributes, String members) {
        // Given a valid listener with one target that additionally writes the setting
        JavaFileObject listener = listener(typeAnnotations, listenerAttributes, members);

        // When the processor compiles it
        var result = compile(listener);

        // Then compilation fails and an error names CarrierSignals and the setting
        assertErrorNaming(result, "CarrierSignals", setting);
    }

    static Stream<Arguments> settingsTheBindingWouldDrop() {
        return Stream.of(
                Arguments.of("deadLetterTopic", "", ", deadLetterTopic = \"carrier.dlq\"", validTarget()),
                Arguments.of("valueType", "", ", valueType = ShipmentDelivered.class", validTarget()),
                Arguments.of("JsonProfile", "@JsonProfile(\"profile\")", "", validTarget()),
                Arguments.of("JsonProfile", "", "", "@JsonProfile(\"profile\")\n" + validTarget()));
    }

    // --- the shapes the generated source cannot honour ---

    @ParameterizedTest(name = "{0}")
    @MethodSource("shapesTheSourceCannotHonour")
    @DisplayName("a listener shape the generated source cannot honour fails compilation naming it")
    void listenerShapeTheGeneratedSourceCannotHonourFailsCompilationNamingIt(
            String shape, JavaFileObject listener, List<String> named) {
        // Given a listener with the shape

        // When the processor compiles it
        var result = compile(listener);

        // Then compilation fails and an error names CarrierSignals and the shape
        assertErrorNaming(result, concat("CarrierSignals", named));
    }

    static Stream<Arguments> shapesTheSourceCannotHonour() {
        return Stream.of(
                Arguments.of(
                        "a @KafkaHandler method beside a target",
                        listener("", "", validTarget() + """

                                @KafkaHandler(defaultHandler = true)
                                void onOther();
                                """),
                        List.of("KafkaHandler")),
                Arguments.of(
                        "two message types on one interface",
                        listener("", "", validTarget() + """

                                @WorkflowSignalTarget(contract = OrdersContract.class, %s, key = "carrierRef")
                                void carrierCancellation(ShipmentCancelled message);
                                """.formatted(CANCELLED)),
                        List.of("ShipmentDelivered", "ShipmentCancelled")),
                Arguments.of(
                        "a target method with no parameter",
                        listener("", "", """
                                @WorkflowSignalTarget(contract = OrdersContract.class, %s, key = "carrierRef")
                                void carrierDelivery();
                                """.formatted(DELIVERED)),
                        List.of("parameter")),
                Arguments.of(
                        "a target method with two parameters",
                        listener("", "", """
                                @WorkflowSignalTarget(contract = OrdersContract.class, %s, key = "carrierRef")
                                void carrierDelivery(ShipmentDelivered message, String extra);
                                """.formatted(DELIVERED)),
                        List.of("parameter")),
                Arguments.of(
                        "a target with both key and businessKey",
                        listener("", "", """
                                @WorkflowSignalTarget(contract = OrdersContract.class, %s, key = "carrierRef",
                                        businessKey = "orderNumber", dedupBy = "eventId")
                                void carrierDelivery(ShipmentDelivered message);
                                """.formatted(DELIVERED)),
                        List.of("businessKey")),
                Arguments.of(
                        "a target with neither key nor businessKey",
                        listener("", "", """
                                @WorkflowSignalTarget(contract = OrdersContract.class, %s)
                                void carrierDelivery(ShipmentDelivered message);
                                """.formatted(DELIVERED)),
                        List.of("businessKey")),
                Arguments.of(
                        "a target on a method of a concrete class",
                        concreteListener(),
                        List.of("WorkflowSignalTarget")));
    }

    // --- fixtures ---

    private static String validTarget() {
        return """
                @WorkflowSignalTarget(contract = OrdersContract.class, %s, key = "carrierRef")
                void carrierDelivery(ShipmentDelivered message);
                """.formatted(DELIVERED);
    }

    /**
     * The interface {@code CarrierSignals}, a {@code @KafkaListener} that may write further attributes and carries
     * the annotations on the type and the members the case needs.
     */
    private static JavaFileObject listener(String typeAnnotations, String extraAttributes, String members) {
        return SourceFiles.inline(
                PACKAGE + ".CarrierSignals", """
                package dev.vertique.examples;

                import dev.vertique.core.json.JsonProfile;
                import dev.vertique.kafka.CommitStrategy;
                import dev.vertique.kafka.ErrorStrategy;
                import dev.vertique.kafka.KafkaHandler;
                import dev.vertique.kafka.KafkaListener;
                import dev.vertique.workflow.source.WorkflowSignalTarget;

                %s
                @KafkaListener(name = "carrier-events", topic = "carrier.events", groupId = "logistics"%s)
                public interface CarrierSignals {
                %s
                }
                """.formatted(typeAnnotations, extraAttributes, members));
    }

    /** A class, not an interface, annotated {@code @KafkaListener} that is a valid direct handler and carries a target. */
    private static JavaFileObject concreteListener() {
        return SourceFiles.inline(PACKAGE + ".CarrierSignals", """
                package dev.vertique.examples;

                import dev.vertique.kafka.KafkaListener;
                import dev.vertique.kafka.KafkaMessage;
                import dev.vertique.kafka.KafkaRecordHandler;
                import dev.vertique.workflow.source.WorkflowSignalTarget;
                import io.vertx.core.Future;

                @KafkaListener(name = "carrier-events", topic = "carrier.events", groupId = "logistics")
                public class CarrierSignals implements KafkaRecordHandler<ShipmentDelivered> {

                    @WorkflowSignalTarget(contract = OrdersContract.class, %s, key = "carrierRef")
                    public void carrierDelivery(ShipmentDelivered message) {}

                    @Override
                    public Future<Void> handle(KafkaMessage<ShipmentDelivered> message) {
                        return Future.succeededFuture();
                    }
                }
                """.formatted(DELIVERED));
    }

    private static ProcessorTestHarness.Result compile(JavaFileObject listener) {
        return ProcessorTestHarness.run(
                new KafkaConsumerProcessor(),
                SIGNAL_NO_MATCH,
                SIGNAL_TARGET,
                SIGNAL_TARGETS,
                WORKFLOW_CONTRACT,
                WORKFLOW_SIGNAL,
                SIGNAL_DEDUP_KEY,
                WORKFLOW_INSTANCE_ID,
                SHIPMENT_DELIVERED,
                SHIPMENT_CANCELLED,
                DELIVERY_CONFIRMED,
                ORDERS_CONTRACT,
                CONFIRMATION_CONTRACT,
                listener);
    }

    /** Asserts that compilation failed and that one single error contains every fragment. */
    private static void assertErrorNaming(ProcessorTestHarness.Result result, String... fragments) {
        result.assertFailed();
        List<String> errors = result.compilation().errors().stream()
                .map(diagnostic -> diagnostic.getMessage(null))
                .toList();
        assertTrue(
                errors.stream().anyMatch(message -> Arrays.stream(fragments).allMatch(message::contains)),
                () -> "expected one error that contains all of " + List.of(fragments) + " but the errors were "
                        + errors);
    }

    private static String[] concat(String first, List<String> rest) {
        return Stream.concat(Stream.of(first), rest.stream()).toArray(String[]::new);
    }
}
