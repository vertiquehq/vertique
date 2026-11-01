// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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

/**
 * Tests the one rule that decides whether a type is a signal listener and the one rule that names the class generated
 * for it, through a real annotation-processing compilation of inline sources. The annotations are inline stubs, because
 * the rule identifies them by fully qualified name and this module takes no dependency on the Kafka or workflow modules.
 */
class SignalListenersTest {

    private static final String PACKAGE = "dev.vertique.examples";

    private static final String KAFKA_LISTENER = """
            package dev.vertique.kafka;

            import java.lang.annotation.*;

            @Target(ElementType.TYPE)
            @Retention(RetentionPolicy.RUNTIME)
            public @interface KafkaListener {
                String name();
                String topic();
                String groupId();
            }
            """;

    private static final String KAFKA_HANDLER = """
            package dev.vertique.kafka;

            import java.lang.annotation.*;

            @Target(ElementType.METHOD)
            @Retention(RetentionPolicy.RUNTIME)
            public @interface KafkaHandler {}
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

    private static final String LISTENER =
            "@KafkaListener(name = \"carriers\", topic = \"carrier.events\", groupId = \"logistics\")";
    private static final String TARGET =
            "@WorkflowSignalTarget(contract = Object.class, signal = \"shipment-delivered\", key = \"carrierRef\")";

    private static final String[] IMPORTS = {
        "import dev.vertique.kafka.KafkaHandler;",
        "import dev.vertique.kafka.KafkaListener;",
        "import dev.vertique.workflow.source.WorkflowSignalTarget;"
    };

    @Test
    @DisplayName(
            "only a @KafkaListener interface with a signal target is a signal listener, and its generated name is stable")
    void onlyAKafkaListenerInterfaceWithASignalTargetIsASignalListenerAndItsGeneratedNameIsStable() {
        // Given an interface with @KafkaListener and one target; one with two targets (the container form); one with
        // @KafkaListener and only @KafkaHandler methods; a concrete class with @KafkaListener and a target method; an
        // interface with a target method and no @KafkaListener; one that only extends it; and a nested interface
        // Outer.Inner of the first kind
        List<JavaFileObject> sources = List.of(
                source("dev.vertique.kafka.KafkaListener", KAFKA_LISTENER),
                source("dev.vertique.kafka.KafkaHandler", KAFKA_HANDLER),
                source("dev.vertique.workflow.source.SignalNoMatch", SIGNAL_NO_MATCH),
                source("dev.vertique.workflow.source.WorkflowSignalTarget", SIGNAL_TARGET),
                source("dev.vertique.workflow.source.WorkflowSignalTargets", SIGNAL_TARGETS),
                example(
                        "SingleTarget",
                        LISTENER + "\npublic interface SingleTarget {\n    " + TARGET
                                + "\n    void delivered(Object message);\n}"),
                example(
                        "ContainerForm",
                        LISTENER + "\npublic interface ContainerForm {\n    " + TARGET + "\n    "
                                + TARGET.replace("shipment-delivered", "parcel-delivered")
                                + "\n    void delivered(Object message);\n}"),
                example(
                        "HandlerOnly",
                        LISTENER
                                + "\npublic interface HandlerOnly {\n    @KafkaHandler\n    void delivered(Object message);\n}"),
                example(
                        "ConcreteListener",
                        LISTENER + "\npublic class ConcreteListener {\n    " + TARGET
                                + "\n    public void delivered(Object message) {}\n}"),
                example(
                        "NotAListener",
                        "public interface NotAListener {\n    " + TARGET + "\n    void delivered(Object message);\n}"),
                example("InheritedOnly", LISTENER + "\npublic interface InheritedOnly extends NotAListener {}"),
                example(
                        "Outer",
                        "public final class Outer {\n    " + LISTENER + "\n    public interface Inner {\n        "
                                + TARGET + "\n        void delivered(Object message);\n    }\n}"));

        // When the rule is asked, from a processor, about each type and for the generated name of the first and nested
        Observation observed = observe(sources);

        // Then the single-target and container-form interfaces are signal listeners, and the other three are not
        assertTrue(observed.listener().get("SingleTarget"));
        assertTrue(observed.listener().get("ContainerForm"));
        assertFalse(observed.listener().get("HandlerOnly"));
        assertFalse(observed.listener().get("ConcreteListener"));
        assertFalse(observed.listener().get("NotAListener"));
        // And an interface that only inherits a target is a signal listener, so the scan can refuse it
        assertTrue(observed.listener().get("InheritedOnly"));
        // And the nested interface of the first kind is a signal listener
        assertTrue(observed.listener().get("Outer.Inner"));
        // And the names are <Interface>_SignalSource in the interface's package, and Outer_Inner_SignalSource nested
        assertEquals(
                PACKAGE + ".SingleTarget_SignalSource", observed.generatedName().get("SingleTarget"));
        assertEquals(
                PACKAGE + ".Outer_Inner_SignalSource", observed.generatedName().get("Outer.Inner"));
    }

    // --- harness ---

    private static final List<String> LISTENED = List.of(
            "SingleTarget",
            "ContainerForm",
            "HandlerOnly",
            "ConcreteListener",
            "NotAListener",
            "InheritedOnly",
            "Outer.Inner");

    private record Observation(Map<String, Boolean> listener, Map<String, String> generatedName) {}

    private static Observation observe(List<JavaFileObject> sources) {
        RuleProbeProcessor processor = new RuleProbeProcessor();
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, null)) {
            JavaCompiler.CompilationTask task = compiler.getTask(
                    null, fileManager, diagnostics, List.of("--release", "21", "-proc:only"), null, sources);
            task.setProcessors(List.of(processor));
            boolean success = Boolean.TRUE.equals(task.call());
            assertTrue(success, () -> "the fixtures must compile but reported " + diagnostics.getDiagnostics());
        } catch (IOException e) {
            throw new AssertionError("Could not close the in-memory compiler", e);
        }
        assertTrue(
                diagnostics.getDiagnostics().stream().noneMatch(d -> d.getKind() == Diagnostic.Kind.ERROR),
                "the rule must report no error of its own");
        return new Observation(processor.listener, processor.generatedName);
    }

    private static JavaFileObject example(String simpleName, String body) {
        return source(
                PACKAGE + "." + simpleName,
                "package " + PACKAGE + ";\n\n" + String.join("\n", IMPORTS) + "\n\n" + body + "\n");
    }

    private static JavaFileObject source(String fqn, String content) {
        return new InMemorySource(fqn, content);
    }

    @SupportedAnnotationTypes("*")
    @SupportedSourceVersion(SourceVersion.RELEASE_21)
    private static final class RuleProbeProcessor extends AbstractProcessor {

        private final Map<String, Boolean> listener = new LinkedHashMap<>();
        private final Map<String, String> generatedName = new LinkedHashMap<>();

        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnvironment) {
            if (roundEnvironment.processingOver() || !listener.isEmpty()) {
                return false;
            }
            for (String name : LISTENED) {
                TypeElement type = processingEnv.getElementUtils().getTypeElement(PACKAGE + "." + name);
                listener.put(name, SignalListeners.isSignalListener(type));
                if (SignalListeners.isSignalListener(type)) {
                    generatedName.put(name, SignalListeners.generatedName(type));
                }
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
