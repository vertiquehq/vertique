// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.workflow.signal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.workflow.signal.SignalTargetScan.DedupRequirement;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests the one scan of {@code @WorkflowSignalTarget} that every transport shares: the shape rules apply under both
 * settings of the {@code dedupBy} requirement, and only the requirement itself follows the parameter. The scan runs from
 * a probe processor over inline sources, one file per case, so that the diagnostics of a case are the errors reported in
 * its own file. The annotations are inline stubs, because the scan identifies them by fully qualified name.
 */
class SignalTargetScanTest {

    private static final String PACKAGE = "dev.vertique.examples";

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

    private static final String WORKFLOW_CONTRACT = """
            package dev.vertique.workflow.contract;

            import java.lang.annotation.*;

            @Target(ElementType.TYPE)
            @Retention(RetentionPolicy.RUNTIME)
            public @interface WorkflowContract {
                String value() default "";
            }
            """;

    private static final String WORKFLOW_SIGNAL = """
            package dev.vertique.workflow.contract;

            import java.lang.annotation.*;

            @Target(ElementType.METHOD)
            @Retention(RetentionPolicy.RUNTIME)
            public @interface WorkflowSignal {
                String value();
            }
            """;

    private static final String SIGNAL_DEDUP_KEY = """
            package dev.vertique.workflow.contract;

            import java.lang.annotation.*;

            @Target(ElementType.PARAMETER)
            @Retention(RetentionPolicy.RUNTIME)
            public @interface SignalDedupKey {}
            """;

    private static final String WORKFLOW_INSTANCE_ID = """
            package dev.vertique.workflow.ops;

            public record WorkflowInstanceId(java.util.UUID value) {}
            """;

    private static final String FUTURE = """
            package io.vertx.core;

            public interface Future<T> {}
            """;

    private static final String MESSAGE = """
            package dev.vertique.examples;

            public record Msg(String eventId, String carrierRef, String orderNumber) {}
            """;

    private static final String OTHER_MESSAGE = """
            package dev.vertique.examples;

            public record OtherMsg(String eventId, String carrierRef) {}
            """;

    private static final String PLAIN_MESSAGE = """
            package dev.vertique.examples;

            public class PlainMsg {
                public String reference() { return ""; }
                public String getEventId() { return ""; }
                public String isTag() { return ""; }
            }
            """;

    private static final String NUMBERED_MESSAGE = """
            package dev.vertique.examples;

            public record NumberedMsg(String eventId, long count) {}
            """;

    private static final String GENERIC_MESSAGE = """
            package dev.vertique.examples;

            public record GenericMsg<T>(String eventId, T body) {}
            """;

    private static final String CONTRACT = """
            package dev.vertique.examples;

            import dev.vertique.workflow.contract.SignalDedupKey;
            import dev.vertique.workflow.contract.WorkflowContract;
            import dev.vertique.workflow.contract.WorkflowSignal;
            import dev.vertique.workflow.ops.WorkflowInstanceId;
            import io.vertx.core.Future;

            @WorkflowContract("orders")
            public interface OrdersContract {
                @WorkflowSignal("shipment-delivered")
                Future<Void> shipmentDelivered(WorkflowInstanceId instanceId, @SignalDedupKey String dedupKey, Msg payload);

                @WorkflowSignal("parcel-delivered")
                Future<Void> parcelDelivered(WorkflowInstanceId instanceId, @SignalDedupKey String dedupKey, Msg payload);

                @WorkflowSignal("other-delivered")
                Future<Void> otherDelivered(WorkflowInstanceId instanceId, @SignalDedupKey String dedupKey, OtherMsg payload);

                @WorkflowSignal("plain-delivered")
                Future<Void> plainDelivered(WorkflowInstanceId instanceId, @SignalDedupKey String dedupKey, PlainMsg payload);

                @WorkflowSignal("numbered-delivered")
                Future<Void> numberedDelivered(WorkflowInstanceId instanceId, @SignalDedupKey String dedupKey, NumberedMsg payload);

                @WorkflowSignal("generic-delivered")
                Future<Void> genericDelivered(WorkflowInstanceId instanceId, @SignalDedupKey String dedupKey, GenericMsg<String> payload);
            }
            """;

    /** One case: its type, its methods, and the declaration the probe scans. */
    private record Case(String type, List<String> names, String declaration) {

        String source() {
            return """
                    package dev.vertique.examples;

                    import dev.vertique.workflow.source.WorkflowSignalTarget;

                    %s
                    """.formatted(declaration);
        }
    }

    private static final String SHIPMENT = "contract = OrdersContract.class, signal = \"shipment-delivered\"";
    private static final String PARCEL = "contract = OrdersContract.class, signal = \"parcel-delivered\"";
    private static final String OTHER = "contract = OrdersContract.class, signal = \"other-delivered\"";
    private static final String PLAIN = "contract = OrdersContract.class, signal = \"plain-delivered\"";
    private static final String NUMBERED = "contract = OrdersContract.class, signal = \"numbered-delivered\"";
    private static final String GENERIC = "contract = OrdersContract.class, signal = \"generic-delivered\"";

    private static final List<Case> CASES = List.of(
            new Case("BusinessKeyWithoutDedupBy", List.of("delivered"), """
                    public interface BusinessKeyWithoutDedupBy {
                        @WorkflowSignalTarget(%s, businessKey = "orderNumber")
                        void delivered(Msg message);
                    }
                    """.formatted(SHIPMENT)),
            new Case("KeyWithoutDedupBy", List.of("delivered"), """
                    public interface KeyWithoutDedupBy {
                        @WorkflowSignalTarget(%s, key = "carrierRef")
                        void delivered(Msg message);
                    }
                    """.formatted(SHIPMENT)),
            new Case("DisagreeingDedupBy", List.of("delivered"), """
                    public interface DisagreeingDedupBy {
                        @WorkflowSignalTarget(%s, key = "carrierRef", dedupBy = "eventId")
                        @WorkflowSignalTarget(%s, key = "carrierRef", dedupBy = "carrierRef")
                        void delivered(Msg message);
                    }
                    """.formatted(SHIPMENT, PARCEL)),
            new Case("BothKeyAndBusinessKey", List.of("delivered"), """
                    public interface BothKeyAndBusinessKey {
                        @WorkflowSignalTarget(%s, key = "carrierRef", businessKey = "orderNumber", dedupBy = "eventId")
                        void delivered(Msg message);
                    }
                    """.formatted(SHIPMENT)),
            new Case("NeitherKeyNorBusinessKey", List.of("delivered"), """
                    public interface NeitherKeyNorBusinessKey {
                        @WorkflowSignalTarget(%s, dedupBy = "eventId")
                        void delivered(Msg message);
                    }
                    """.formatted(SHIPMENT)),
            new Case("TwoParameters", List.of("delivered"), """
                    public interface TwoParameters {
                        @WorkflowSignalTarget(%s, key = "carrierRef", dedupBy = "eventId")
                        void delivered(Msg message, String extra);
                    }
                    """.formatted(SHIPMENT)),
            new Case("TwoMessageTypes", List.of("delivered", "other"), """
                    public interface TwoMessageTypes {
                        @WorkflowSignalTarget(%s, key = "carrierRef", dedupBy = "eventId")
                        void delivered(Msg message);

                        @WorkflowSignalTarget(%s, key = "carrierRef", dedupBy = "eventId")
                        void other(OtherMsg message);
                    }
                    """.formatted(SHIPMENT, OTHER)),
            new Case("ConcreteTarget", List.of("delivered"), """
                    public class ConcreteTarget {
                        @WorkflowSignalTarget(%s, key = "carrierRef", dedupBy = "eventId")
                        public void delivered(Msg message) {}
                    }
                    """.formatted(SHIPMENT)),
            new Case("BaseTargets", List.of("delivered"), """
                    public interface BaseTargets {
                        @WorkflowSignalTarget(%s, key = "carrierRef", dedupBy = "eventId")
                        void delivered(Msg message);
                    }
                    """.formatted(SHIPMENT)),
            new Case("InheritedTarget", List.of("delivered"), """
                    public interface InheritedTarget extends BaseTargets {}
                    """),
            new Case("NonStringKey", List.of("delivered"), """
                    public interface NonStringKey {
                        @WorkflowSignalTarget(%s, key = "count", dedupBy = "eventId")
                        void delivered(NumberedMsg message);
                    }
                    """.formatted(NUMBERED)),
            new Case("GenericMessage", List.of("delivered"), """
                    public interface GenericMessage {
                        @WorkflowSignalTarget(%s, key = "eventId", dedupBy = "eventId")
                        void delivered(GenericMsg<String> message);
                    }
                    """.formatted(GENERIC)),
            new Case("PlainMessageAccessors", List.of("delivered"), """
                    public interface PlainMessageAccessors {
                        @WorkflowSignalTarget(%s, key = "reference", dedupBy = "eventId")
                        @WorkflowSignalTarget(%s, businessKey = "tag", dedupBy = "eventId")
                        void delivered(PlainMsg message);
                    }
                    """.formatted(PLAIN, PLAIN)),
            new Case("AgreeingTargets", List.of("delivered"), """
                    public interface AgreeingTargets {
                        @WorkflowSignalTarget(%s, key = "carrierRef", dedupBy = "eventId")
                        @WorkflowSignalTarget(%s, businessKey = "orderNumber", dedupBy = "eventId")
                        void delivered(Msg message);
                    }
                    """.formatted(SHIPMENT, PARCEL)));

    private static final Map<DedupRequirement, Outcome> OUTCOMES = new EnumMap<>(DedupRequirement.class);

    /** What the probe saw for one setting: the targets each case yielded and the errors in each case's file. */
    private record Outcome(
            Map<String, Integer> targets, Map<String, List<String>> errors, Map<String, List<String>> methods) {}

    @ParameterizedTest(name = "{0}")
    @MethodSource("rows")
    @DisplayName("shape rules apply under both settings and the dedupBy requirement follows the parameter")
    void sharedShapeRulesApplyUnderBothSettingsAndDedupByRequirementFollowsTheParameter(
            String type, int targets, boolean validUnderBusinessKeyTargets, boolean validUnderEveryTarget) {
        // Given the case's interface, scanned once with the requirement set to business-key targets and once to every
        // target
        // When the probe processor runs the scan under each setting
        Outcome businessKeyTargets = outcome(DedupRequirement.BUSINESS_KEY_TARGETS);
        Outcome everyTarget = outcome(DedupRequirement.EVERY_TARGET);

        // Then the case is valid or refused as its row states under each setting
        assertCase(type, targets, validUnderBusinessKeyTargets, businessKeyTargets);
        assertCase(type, targets, validUnderEveryTarget, everyTarget);
    }

    static Stream<Arguments> rows() {
        return Stream.of(
                // the business-key target without dedupBy is refused under both
                Arguments.of("BusinessKeyWithoutDedupBy", 1, false, false),
                // the key target without dedupBy is valid only under the first
                Arguments.of("KeyWithoutDedupBy", 1, true, false),
                // the shape cases are refused under both
                Arguments.of("DisagreeingDedupBy", 2, false, false),
                Arguments.of("BothKeyAndBusinessKey", 1, false, false),
                Arguments.of("NeitherKeyNorBusinessKey", 1, false, false),
                Arguments.of("TwoParameters", 1, false, false),
                Arguments.of("TwoMessageTypes", 2, false, false),
                Arguments.of("ConcreteTarget", 1, false, false),
                // a target inherited from a super-interface, a key whose accessor is no String and a generic message
                // are
                // refused under both, and the super-interface that declares the target is itself valid
                Arguments.of("BaseTargets", 1, true, true),
                Arguments.of("InheritedTarget", 0, false, false),
                Arguments.of("NonStringKey", 1, false, false),
                Arguments.of("GenericMessage", 1, false, false),
                // a class message with a no-argument accessor named like the property, and get and is accessors
                Arguments.of("PlainMessageAccessors", 2, true, true),
                // the valid interface yields its two targets under both
                Arguments.of("AgreeingTargets", 2, true, true));
    }

    private static void assertCase(String type, int targets, boolean valid, Outcome outcome) {
        Case row = CASES.stream().filter(c -> c.type().equals(type)).findFirst().orElseThrow();
        List<String> errors = outcome.errors().get(type);
        if (valid) {
            assertEquals(List.of(), errors, type + " must be accepted");
            assertEquals(Integer.valueOf(targets), outcome.targets().get(type), type + " must yield its targets");
            return;
        }
        assertTrue(!errors.isEmpty(), type + " must be refused with an error reported in its own file");
        assertTrue(
                errors.stream()
                        .anyMatch(message ->
                                message.contains(type) || row.names().stream().anyMatch(message::contains)),
                () -> "the error of " + type + " must name the offending element but was " + errors);
    }

    @Test
    @DisplayName("a target carries the method that reads its key and its dedup key, bare name first")
    void targetCarriesTheResolvedAccessorMethods() {
        // Given a message class with a bare-name method, a get method and an is method, and a record
        // When the probe scans the interfaces over them
        Outcome outcome = outcome(DedupRequirement.BUSINESS_KEY_TARGETS);

        // Then each target names the method it resolved, for the key and for the dedup key
        assertEquals(
                List.of("reference/getEventId", "isTag/getEventId"),
                outcome.methods().get("PlainMessageAccessors"));
        assertEquals(List.of("carrierRef/eventId"), outcome.methods().get("BaseTargets"));
    }

    // --- harness ---

    private static synchronized Outcome outcome(DedupRequirement requirement) {
        return OUTCOMES.computeIfAbsent(requirement, SignalTargetScanTest::scanAll);
    }

    private static Outcome scanAll(DedupRequirement requirement) {
        ScanProbeProcessor processor = new ScanProbeProcessor(requirement);
        List<JavaFileObject> sources = new ArrayList<>(List.of(
                source("dev.vertique.workflow.source.SignalNoMatch", SIGNAL_NO_MATCH),
                source("dev.vertique.workflow.source.WorkflowSignalTarget", SIGNAL_TARGET),
                source("dev.vertique.workflow.source.WorkflowSignalTargets", SIGNAL_TARGETS),
                source("dev.vertique.workflow.contract.WorkflowContract", WORKFLOW_CONTRACT),
                source("dev.vertique.workflow.contract.WorkflowSignal", WORKFLOW_SIGNAL),
                source("dev.vertique.workflow.contract.SignalDedupKey", SIGNAL_DEDUP_KEY),
                source("dev.vertique.workflow.ops.WorkflowInstanceId", WORKFLOW_INSTANCE_ID),
                source("io.vertx.core.Future", FUTURE),
                source(PACKAGE + ".Msg", MESSAGE),
                source(PACKAGE + ".OtherMsg", OTHER_MESSAGE),
                source(PACKAGE + ".PlainMsg", PLAIN_MESSAGE),
                source(PACKAGE + ".NumberedMsg", NUMBERED_MESSAGE),
                source(PACKAGE + ".GenericMsg", GENERIC_MESSAGE),
                source(PACKAGE + ".OrdersContract", CONTRACT)));
        CASES.forEach(c -> sources.add(source(PACKAGE + "." + c.type(), c.source())));

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, null)) {
            JavaCompiler.CompilationTask task = compiler.getTask(
                    null, fileManager, diagnostics, List.of("--release", "21", "-proc:only"), null, sources);
            task.setProcessors(List.of(processor));
            task.call();
        } catch (IOException e) {
            throw new AssertionError("Could not close the in-memory compiler", e);
        }
        Map<String, List<String>> errors = new LinkedHashMap<>();
        for (Case c : CASES) {
            errors.put(
                    c.type(),
                    diagnostics.getDiagnostics().stream()
                            .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                            .filter(d -> d.getSource() != null
                                    && d.getSource().getName().endsWith("/" + c.type() + ".java"))
                            .map(d -> d.getMessage(Locale.ROOT))
                            .toList());
        }
        List<String> stray = diagnostics.getDiagnostics().stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                .filter(d -> d.getSource() == null
                        || CASES.stream().noneMatch(c -> d.getSource().getName().endsWith("/" + c.type() + ".java")))
                .map(d -> d.getMessage(Locale.ROOT))
                .toList();
        assertEquals(List.of(), stray, "the support sources must compile and the scan must report on the cases only");
        return new Outcome(processor.targets, errors, processor.methods);
    }

    private static JavaFileObject source(String fqn, String content) {
        return new InMemorySource(fqn, content);
    }

    @SupportedAnnotationTypes("*")
    @SupportedSourceVersion(SourceVersion.RELEASE_21)
    private static final class ScanProbeProcessor extends AbstractProcessor {

        private final DedupRequirement requirement;
        private final Map<String, Integer> targets = new LinkedHashMap<>();
        private final Map<String, List<String>> methods = new LinkedHashMap<>();

        private ScanProbeProcessor(DedupRequirement requirement) {
            this.requirement = requirement;
        }

        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnvironment) {
            if (roundEnvironment.processingOver() || !targets.isEmpty()) {
                return false;
            }
            CodegenContext context = new CodegenContext(processingEnv);
            for (Case c : CASES) {
                TypeElement type = processingEnv.getElementUtils().getTypeElement(PACKAGE + "." + c.type());
                List<SignalTargetScan.Target> scanned = SignalTargetScan.scan(context, type, requirement);
                targets.put(c.type(), scanned.size());
                methods.put(
                        c.type(),
                        scanned.stream()
                                .map(target -> target.keyMethod() + "/" + target.dedupByMethod())
                                .toList());
            }
            return false;
        }
    }

    private static final class InMemorySource extends SimpleJavaFileObject {

        private final String source;

        private InMemorySource(String className, String source) {
            super(
                    URI.create("string:///" + className.replace('.', '/') + JavaFileObject.Kind.SOURCE.extension),
                    JavaFileObject.Kind.SOURCE);
            this.source = source;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return source;
        }
    }
}
