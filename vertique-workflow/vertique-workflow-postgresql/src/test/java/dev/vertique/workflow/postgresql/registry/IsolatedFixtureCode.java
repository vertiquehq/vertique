// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.registry;

import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import dev.vertique.workflow.types.DefaultWorkflowTypeNames;
import dev.vertique.workflow.types.WorkflowTypeCatalogEntry;
import dev.vertique.workflow.types.WorkflowTypeCatalogEntry.Kind;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Compiles a small workflow fixture package from source into a temporary directory and loads it through its own child
 * class loader, so one binary name can exist unannotated in one start, annotated in the next, and in another package
 * after a move.
 *
 * <p>The fixture holds a state class {@code OrderState}, a task decision payload {@code Approval} and the definition
 * {@code BindingOrder} with a human task step, all in the given package. The definition is generated in two shapes:
 * one review task, or the same with a second review task, which changes its step graph.
 */
final class IsolatedFixtureCode implements AutoCloseable {

    /** Whether the fixture's state and payload classes carry a logical type name. */
    enum Naming {
        /** The classes carry no annotation, so their logical names are their binary names. */
        UNANNOTATED,
        /** The classes are named {@link #STATE_NAME} and {@link #APPROVAL_NAME} by an annotation. */
        ANNOTATED
    }

    static final String DEFINITION_ID = "binding-order";
    static final String STATE_NAME = "order";
    static final String APPROVAL_NAME = "approval";

    private static final String PACKAGE_TOKEN = "@@PACKAGE@@";
    private static final String ANNOTATION_TOKEN = "@@ANNOTATION@@";
    private static final String SECOND_REVIEW_TOKEN = "@@SECOND_REVIEW@@";
    private static final String FIRST_NEXT_TOKEN = "@@FIRST_NEXT@@";

    private static final String STATE_SOURCE = """
            package @@PACKAGE@@;

            import dev.vertique.workflow.types.WorkflowType;

            @@ANNOTATION@@
            public record OrderState(String id) {}
            """;

    private static final String APPROVAL_SOURCE = """
            package @@PACKAGE@@;

            import dev.vertique.workflow.types.WorkflowType;

            @@ANNOTATION@@
            public record Approval(String by) {}
            """;

    private static final String ORDER_SOURCE = """
            package @@PACKAGE@@;

            import dev.vertique.workflow.dsl.WorkflowBuilder;
            import dev.vertique.workflow.dsl.WorkflowDefinition;
            import dev.vertique.workflow.postgresql.registry.NameBindingIT;

            public final class BindingOrder implements WorkflowDefinition<OrderState, NameBindingIT.BindingContract> {

                private final long version;

                public BindingOrder(long version) {
                    this.version = version;
                }

                @Override
                public Class<NameBindingIT.BindingContract> contract() {
                    return NameBindingIT.BindingContract.class;
                }

                @Override
                public Class<OrderState> stateType() {
                    return OrderState.class;
                }

                @Override
                public String definitionId() {
                    return "binding-order";
                }

                @Override
                public long definitionVersion() {
                    return version;
                }

                @Override
                public void define(WorkflowBuilder<OrderState> wf) {
                    wf.init(NameBindingIT.StartOrder.class, start -> new OrderState(start.id()))
                            .initialStep("review")
                            .task("review")
                            .assignToRole("reviewers")
                            .decision("approve", Approval.class)
                            .onDecision((state, approval) -> state)
                            .toStep("@@FIRST_NEXT@@")
                            .build()
                            @@SECOND_REVIEW@@
                            .complete("done");
                }
            }
            """;

    private static final String SECOND_REVIEW_STEP = """
            .task("second-review")
                            .assignToRole("reviewers")
                            .decision("approve", Approval.class)
                            .onDecision((state, approval) -> state)
                            .toStep("done")
                            .build()""";

    private final Path directory;
    private final URLClassLoader loader;
    private final String packageName;
    private final Naming naming;

    private IsolatedFixtureCode(Path directory, URLClassLoader loader, String packageName, Naming naming) {
        this.directory = directory;
        this.loader = loader;
        this.packageName = packageName;
        this.naming = naming;
    }

    /**
     * Compiles the fixture into the package and loads it through a new class loader below the test's own.
     *
     * @param packageName the package of the fixture classes
     * @param naming whether the state and payload classes are annotated with a logical name
     * @param secondReview whether the definition has a second review task after the first
     */
    static IsolatedFixtureCode compile(String packageName, Naming naming, boolean secondReview) {
        Path directory = null;
        URLClassLoader loader = null;
        try {
            directory = Files.createTempDirectory("name-binding-fixture");
            Path classes = Files.createDirectories(directory.resolve("classes"));
            Path packageDirectory =
                    Files.createDirectories(directory.resolve("src").resolve(packageName.replace('.', '/')));
            boolean annotated = naming == Naming.ANNOTATED;
            List<Path> files = List.of(
                    write(packageDirectory, "OrderState", STATE_SOURCE, packageName, annotated ? STATE_NAME : null),
                    write(packageDirectory, "Approval", APPROVAL_SOURCE, packageName, annotated ? APPROVAL_NAME : null),
                    write(packageDirectory, "BindingOrder", orderSource(secondReview), packageName, null));
            compileSources(files, classes);
            loader =
                    new URLClassLoader(new URL[] {classes.toUri().toURL()}, IsolatedFixtureCode.class.getClassLoader());
            return new IsolatedFixtureCode(directory, loader, packageName, naming);
        } catch (IOException | RuntimeException failure) {
            closeQuietly(loader);
            deleteQuietly(directory);
            if (failure instanceof RuntimeException runtime) throw runtime;
            throw new UncheckedIOException((IOException) failure);
        }
    }

    /** The definition of the given version, created from this fixture's own class loader. */
    WorkflowDefinition<?, ?> definition(long version) {
        try {
            return (WorkflowDefinition<?, ?>) loader.loadClass(packageName + ".BindingOrder")
                    .getConstructor(long.class)
                    .newInstance(version);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not create the fixture definition", e);
        }
    }

    /** The binary name of the fixture's state class. */
    String stateClassName() {
        return packageName + ".OrderState";
    }

    /** The binary name of the fixture's decision payload class. */
    String approvalClassName() {
        return packageName + ".Approval";
    }

    /** The catalog entries a service-loaded document may name: the state and payload classes, by their logical names. */
    List<WorkflowTypeCatalogEntry> catalog() {
        boolean annotated = naming == Naming.ANNOTATED;
        return List.of(
                new WorkflowTypeCatalogEntry(
                        Kind.TYPE, annotated ? STATE_NAME : stateClassName(), load(stateClassName())),
                new WorkflowTypeCatalogEntry(
                        Kind.TYPE, annotated ? APPROVAL_NAME : approvalClassName(), load(approvalClassName())));
    }

    /**
     * The plan hash a registry without a store computes for the version of this fixture: the hash over the classes'
     * current logical names, which is what a version first published from this code stores.
     */
    String planHash(long version) {
        DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry(null, Set.of(), new DefaultWorkflowTypeNames());
        registry.register(definition(version));
        return registry.resolve(new DefinitionKey(WorkflowScopes.DEFAULT, DEFINITION_ID, version))
                .plan()
                .planHash();
    }

    private Class<?> load(String name) {
        try {
            return loader.loadClass(name);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("The fixture class " + name + " is missing", e);
        }
    }

    @Override
    public void close() {
        closeQuietly(loader);
        deleteQuietly(directory);
    }

    private static String orderSource(boolean secondReview) {
        return ORDER_SOURCE
                .replace(FIRST_NEXT_TOKEN, secondReview ? "second-review" : "done")
                .replace(SECOND_REVIEW_TOKEN, secondReview ? SECOND_REVIEW_STEP : "");
    }

    private static Path write(Path directory, String className, String template, String packageName, String typeName)
            throws IOException {
        String annotation = typeName == null ? "" : "@WorkflowType(\"" + typeName + "\")";
        Path file = directory.resolve(className + ".java");
        Files.writeString(
                file,
                template.replace(PACKAGE_TOKEN, packageName).replace(ANNOTATION_TOKEN, annotation),
                StandardCharsets.UTF_8);
        return file;
    }

    private static void compileSources(List<Path> files, Path output) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("A JDK compiler is required to build the fixture");
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        boolean compiled;
        try (StandardJavaFileManager fileManager =
                compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            compiled = compiler.getTask(
                            null,
                            fileManager,
                            diagnostics,
                            List.of("-proc:none", "-classpath", classpath, "-d", output.toString()),
                            null,
                            fileManager.getJavaFileObjectsFromPaths(files))
                    .call();
        }
        if (!compiled) {
            List<String> errors = new ArrayList<>();
            for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
                errors.add(diagnostic.toString());
            }
            throw new IllegalStateException("The fixture did not compile: " + errors);
        }
    }

    private static void closeQuietly(URLClassLoader loader) {
        if (loader == null) return;
        try {
            loader.close();
        } catch (IOException ignored) {
            // The loader only holds a directory that is deleted next.
        }
    }

    private static void deleteQuietly(Path directory) {
        if (directory == null) return;
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // A temporary file that cannot be removed is left to the operating system.
                }
            });
        } catch (IOException ignored) {
            // The directory is already gone.
        }
    }
}
