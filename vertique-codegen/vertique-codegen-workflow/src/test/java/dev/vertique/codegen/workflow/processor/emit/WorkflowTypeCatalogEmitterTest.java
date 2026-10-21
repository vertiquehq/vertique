// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.workflow.processor.emit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import dagger.Module;
import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import dev.vertique.codegen.workflow.processor.WorkflowContractProcessor;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.Completion;
import javax.annotation.processing.Filer;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies the {@code GeneratedWorkflowTypeCatalogModule} the workflow processor emits for the
 * {@code @WorkflowContract} interfaces and {@code @WorkflowType} classes of one compilation: its
 * entries, the package it is emitted in, the classes it leaves out, and the refusal of a module name
 * that a dependency already generated.
 *
 * <p>The catalog entries are read reflectively from the loaded generated module so the assertions
 * observe what a Dagger component would receive, not the shape of the generated source.
 */
class WorkflowTypeCatalogEmitterTest {

    private static final String MODULE_SIMPLE_NAME = "GeneratedWorkflowTypeCatalogModule";
    private static final String CATALOG_FQN = "dev.vertique.workflow.types.WorkflowTypeCatalog";

    // --- Shared fixtures ---

    private static JavaFileObject startPayload(String pkg) {
        return SourceFiles.inline(pkg + ".StartPayload", """
                package %s;
                import dev.vertique.workflow.contract.IdempotencyKeyed;
                public record StartPayload(String id) implements IdempotencyKeyed {
                    @Override public String idempotencyKey() { return id; }
                }
                """.formatted(pkg));
    }

    private static JavaFileObject contract(String pkg, String simpleName, String annotation) {
        return SourceFiles.inline(pkg + "." + simpleName, """
                package %s;
                import dev.vertique.workflow.contract.WorkflowContract;
                import dev.vertique.workflow.contract.WorkflowStart;
                import dev.vertique.workflow.ops.WorkflowInstanceId;
                import io.vertx.core.Future;
                %s
                public interface %s {
                    @WorkflowStart
                    Future<WorkflowInstanceId> start(StartPayload cmd);
                }
                """.formatted(pkg, annotation, simpleName));
    }

    private static JavaFileObject typeClass(String fqn, String logicalName, String modifiers) {
        int dot = fqn.lastIndexOf('.');
        return SourceFiles.inline(
                fqn, """
                package %s;
                import dev.vertique.workflow.types.WorkflowType;
                @WorkflowType("%s")
                %sclass %s {}
                """.formatted(fqn.substring(0, dot), logicalName, modifiers, fqn.substring(dot + 1)));
    }

    // --- Reflective access to a generated module ---

    /** One catalog entry as {@code [kind, name, type]}. */
    private static List<List<Object>> entriesOf(ProcessorTestHarness.Result result, String moduleFqn) {
        Class<?> module = result.loadGeneratedClass(moduleFqn);
        Method provides = null;
        for (Method m : module.getDeclaredMethods()) {
            if (m.getReturnType().getName().equals(CATALOG_FQN)) {
                assertTrue(provides == null, "the module declares more than one catalog method");
                provides = m;
            }
        }
        assertNotNull(provides, "the module declares no method returning a workflow type catalog");
        assertTrue(Modifier.isStatic(provides.getModifiers()), "the catalog method must be static");
        try {
            provides.setAccessible(true);
            Object catalog = provides.invoke(null);
            List<?> entries =
                    (List<?>) Class.forName(CATALOG_FQN).getMethod("entries").invoke(catalog);
            List<List<Object>> out = new ArrayList<>();
            for (Object entry : entries) {
                Class<?> entryType = entry.getClass();
                out.add(List.of(
                        entryType.getMethod("kind").invoke(entry).toString(),
                        entryType.getMethod("name").invoke(entry),
                        entryType.getMethod("type").invoke(entry)));
            }
            return out;
        } catch (InvocationTargetException e) {
            throw new AssertionError("the catalog method failed", e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("the catalog cannot be read", e);
        }
    }

    // --- Entries ---

    @Test
    @DisplayName("a compilation with contracts and types emits a catalog module listing one entry for each")
    void emitsCatalogModuleWithContractAndTypeEntries() {
        JavaFileObject payload = startPayload("com.acme");
        JavaFileObject namedContract =
                contract("com.acme", "OrderWorkflow", "@WorkflowContract(" + '"' + "orders" + '"' + ")");
        JavaFileObject unnamedContract = contract("com.acme", "Refunds", "@WorkflowContract");
        JavaFileObject orderState = typeClass("com.acme.OrderState", "order", "public ");
        JavaFileObject paid = typeClass("com.acme.Paid", "order.paid", "public ");

        ProcessorTestHarness.Result result = ProcessorTestHarness.run(
                        new WorkflowContractProcessor(), payload, namedContract, unnamedContract, orderState, paid)
                .assertSuccess();
        String moduleFqn = "com.acme." + MODULE_SIMPLE_NAME;

        Class<?> module = result.loadGeneratedClass(moduleFqn);
        assertTrue(module.isAnnotationPresent(Module.class), "the generated class must be a Dagger module");
        result.assertGeneratedSourceContains(moduleFqn, "@Provides")
                .assertGeneratedSourceContains(moduleFqn, "@IntoSet");
        assertEquals(
                List.of(
                        List.of("CONTRACT", "com.acme.Refunds", result.loadGeneratedClass("com.acme.Refunds")),
                        List.of("CONTRACT", "orders", result.loadGeneratedClass("com.acme.OrderWorkflow")),
                        List.of("TYPE", "order", result.loadGeneratedClass("com.acme.OrderState")),
                        List.of("TYPE", "order.paid", result.loadGeneratedClass("com.acme.Paid"))),
                entriesOf(result, moduleFqn));
    }

    // --- Package rule, skipped types ---

    @Nested
    @DisplayName("a compilation with only workflow types")
    class TypesOnlyUnit {

        @Test
        @DisplayName("emits a catalog module in the package of the type")
        void typesOnlyUnitEmitsCatalogInThePackageOfTheType() {
            ProcessorTestHarness.Result result = ProcessorTestHarness.run(
                            new WorkflowContractProcessor(),
                            typeClass("com.acme.orders.OrderState", "order", "public "))
                    .assertSuccess();

            String moduleFqn = "com.acme.orders." + MODULE_SIMPLE_NAME;
            assertEquals(
                    List.of(List.of("TYPE", "order", result.loadGeneratedClass("com.acme.orders.OrderState"))),
                    entriesOf(result, moduleFqn));
        }
    }

    @Nested
    @DisplayName("a class the module cannot reference")
    class UnreferenceableType {

        @Test
        @DisplayName("is left out of the catalog with a warning and the build still compiles")
        void unreferenceableTypeIsSkippedWithWarning() {
            ProcessorTestHarness.Result result = ProcessorTestHarness.run(
                            new WorkflowContractProcessor(),
                            typeClass("com.acme.orders.OrderState", "order", "public "),
                            typeClass("com.acme.hidden.Hidden", "hidden", ""))
                    .assertSuccess()
                    .assertWarningMessage("com.acme.hidden.Hidden");

            String moduleFqn = "com.acme." + MODULE_SIMPLE_NAME;
            result.assertGeneratedSourceDoesNotContain(moduleFqn, "Hidden");
            assertEquals(
                    List.of(List.of("TYPE", "order", result.loadGeneratedClass("com.acme.orders.OrderState"))),
                    entriesOf(result, moduleFqn));
        }
    }

    @Nested
    @DisplayName("a compilation whose types share no package")
    class NoCommonPackage {

        private final JavaFileObject foo = typeClass("com.foo.Foo", "foo", "public ");
        private final JavaFileObject bar = typeClass("org.bar.Bar", "bar", "public ");

        @Test
        @DisplayName("emits the module in the default package")
        void emitsInTheDefaultPackage() {
            ProcessorTestHarness.Result result = ProcessorTestHarness.run(new WorkflowContractProcessor(), foo, bar)
                    .assertSuccess();

            assertEquals(
                    List.of(
                            List.of("TYPE", "bar", result.loadGeneratedClass("org.bar.Bar")),
                            List.of("TYPE", "foo", result.loadGeneratedClass("com.foo.Foo"))),
                    entriesOf(result, "vertique.generated.workflow." + MODULE_SIMPLE_NAME));
        }

        @Test
        @DisplayName("emits the module in the package given by the codegen package option")
        void emitsInTheOptionPackage() {
            ProcessorTestHarness.Result result = ProcessorTestHarness.run(
                            new WorkflowContractProcessor(),
                            Map.of("vertique.codegen.package", "com.override"),
                            foo,
                            bar)
                    .assertSuccess();

            assertEquals(
                    List.of(
                            List.of("TYPE", "bar", result.loadGeneratedClass("org.bar.Bar")),
                            List.of("TYPE", "foo", result.loadGeneratedClass("com.foo.Foo"))),
                    entriesOf(result, "com.override." + MODULE_SIMPLE_NAME));
        }
    }

    // --- Collision with a module generated by a dependency ---

    @Test
    @DisplayName("a module name a dependency already generated fails the build with a diagnostic")
    void collidingGeneratedModuleNameFailsTheBuildWithADiagnostic(@TempDir Path dependencyClasses) throws IOException {
        String moduleFqn = "com.acme." + MODULE_SIMPLE_NAME;
        JavaFileObject other = typeClass("com.acme.Other", "other", "public ");
        compileDependencyInto(dependencyClasses, moduleFqn);

        Outcome collision = compileWithDependency(dependencyClasses, Map.of(), other);
        Outcome control =
                compileWithDependency(dependencyClasses, Map.of("vertique.codegen.package", "com.acme.other"), other);

        assertEquals(
                Compilation.Status.FAILURE, collision.compilation().status(), "the colliding compilation must fail");
        List<String> errors = collision.compilation().errors().stream()
                .map(d -> d.getMessage(null))
                .toList();
        assertTrue(
                errors.stream().anyMatch(m -> m.contains(moduleFqn) && m.contains("vertique.codegen.package")),
                "expected an error naming " + moduleFqn + " and the package option but got " + errors);
        assertEquals(List.of(), collision.writtenSources(), "a colliding compilation must not write any source");
        assertEquals(
                Compilation.Status.SUCCESS, control.compilation().status(), "the control compilation must succeed");
        assertTrue(
                control.compilation()
                        .generatedSourceFile("com.acme.other." + MODULE_SIMPLE_NAME)
                        .isPresent(),
                "the control compilation must generate the module in the option package");
    }

    /**
     * First compilation: builds a dependency that already holds {@code moduleFqn} and writes its class
     * files under {@code targetDir}, as a dependency jar's content would sit on a classpath. The module
     * is declared by hand because only its name matters to the compilation that collides with it.
     */
    private static void compileDependencyInto(Path targetDir, String moduleFqn) throws IOException {
        int dot = moduleFqn.lastIndexOf('.');
        JavaFileObject dependencyModule =
                SourceFiles.inline(moduleFqn, """
                package %s;
                @dagger.Module
                public final class %s {}
                """.formatted(moduleFqn.substring(0, dot), moduleFqn.substring(dot + 1)));
        Compilation first = Compiler.javac().withOptions("--release", "21").compile(dependencyModule);
        assertEquals(Compilation.Status.SUCCESS, first.status(), "the dependency must compile");
        for (JavaFileObject file : first.generatedFiles()) {
            if (file.getKind() != JavaFileObject.Kind.CLASS) {
                continue;
            }
            // mem:///CLASS_OUTPUT/com/acme/X.class -> com/acme/X.class
            String path = file.toUri().getPath();
            Path target = targetDir.resolve(path.substring(path.indexOf('/', 1) + 1));
            Files.createDirectories(target.getParent());
            try (InputStream in = file.openInputStream()) {
                Files.write(target, in.readAllBytes());
            }
        }
        assertTrue(Files.exists(targetDir.resolve(moduleFqn.replace('.', '/') + ".class")));
    }

    /** A compilation together with the names of the source files its processor asked the filer to create. */
    private record Outcome(Compilation compilation, List<String> writtenSources) {}

    /** Second compilation: the processor under test with the dependency's classes on the classpath. */
    private static Outcome compileWithDependency(
            Path dependencyClasses, Map<String, String> processorOptions, JavaFileObject... sources) {
        List<File> classpath = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            classpath.add(new File(entry));
        }
        classpath.add(dependencyClasses.toFile());
        List<String> options = new ArrayList<>(List.of("--release", "21"));
        processorOptions.forEach((key, value) -> options.add("-A" + key + "=" + value));
        FilerRecordingProcessor processor = new FilerRecordingProcessor(new WorkflowContractProcessor());
        Compilation compilation = Compiler.javac()
                .withProcessors(processor)
                .withOptions(options)
                .withClasspath(classpath)
                .compile(sources);
        return new Outcome(compilation, List.copyOf(processor.writtenSources));
    }

    /**
     * Runs another processor unchanged and records every source file it asks the filer to create, so a
     * test can see what was written even when the compilation fails and discards its outputs.
     */
    private static final class FilerRecordingProcessor implements Processor {

        private final Processor delegate;
        final List<String> writtenSources = new ArrayList<>();

        FilerRecordingProcessor(Processor delegate) {
            this.delegate = delegate;
        }

        @Override
        public void init(ProcessingEnvironment env) {
            Filer filer = (Filer) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[] {Filer.class}, (proxy, method, args) -> {
                        if (method.getName().equals("createSourceFile")) {
                            writtenSources.add(args[0].toString());
                        }
                        return invoke(method, env.getFiler(), args);
                    });
            delegate.init((ProcessingEnvironment) Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[] {ProcessingEnvironment.class},
                    (proxy, method, args) -> method.getName().equals("getFiler") ? filer : invoke(method, env, args)));
        }

        private static Object invoke(Method method, Object target, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }

        @Override
        public Set<String> getSupportedOptions() {
            return delegate.getSupportedOptions();
        }

        @Override
        public Set<String> getSupportedAnnotationTypes() {
            return delegate.getSupportedAnnotationTypes();
        }

        @Override
        public SourceVersion getSupportedSourceVersion() {
            return delegate.getSupportedSourceVersion();
        }

        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
            return delegate.process(annotations, roundEnv);
        }

        @Override
        public Iterable<? extends Completion> getCompletions(
                Element element, AnnotationMirror annotation, ExecutableElement member, String userText) {
            return delegate.getCompletions(element, annotation, member, userText);
        }
    }
}
