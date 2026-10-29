// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.registry;

import dev.vertique.workflow.dsl.WorkflowDefinition;
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
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Compiles a small workflow fixture into a temporary directory that is on no class path and loads it through a class
 * loader of its own, so that only the nodes handed its definitions can see its classes.
 *
 * <p>The fixture is a review workflow of contract {@code orders}: a start payload, a state, a human task that offers
 * one decision {@code approve} whose payload requires a {@code comment}, and an end. The definition can be created
 * under any id and with or without version stability on its task. Every class lives in {@link #PACKAGE}, a package
 * that no class of the test module's own class path is in.
 */
final class RecordingOnlyFixture implements AutoCloseable {

    /** The package of every fixture class; no class of the test class path is in it. */
    static final String PACKAGE = "fixture.recordingonly";

    /** The name of the fixture's contract. */
    static final String CONTRACT_NAME = "orders";

    /** The id of the fixture's human task step. */
    static final String TASK_STEP = "review";

    /** The decision the fixture's task offers. */
    static final String DECISION = "approve";

    private static final String CONTRACT_SOURCE = """
            package fixture.recordingonly;

            import dev.vertique.workflow.contract.WorkflowContract;

            @WorkflowContract("orders")
            public interface OrdersContract {}
            """;

    private static final String STATE_SOURCE = """
            package fixture.recordingonly;

            public record OrderState(String id) {}
            """;

    private static final String START_SOURCE = """
            package fixture.recordingonly;

            public record StartOrder(String id) {}
            """;

    private static final String APPROVAL_SOURCE = """
            package fixture.recordingonly;

            import com.fasterxml.jackson.annotation.JsonProperty;
            import io.swagger.v3.oas.annotations.media.Schema;

            public record Approval(
                    @JsonProperty(value = "comment", required = true)
                    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String comment) {}
            """;

    private static final String DEFINITION_SOURCE = """
            package fixture.recordingonly;

            import dev.vertique.workflow.dsl.WorkflowBuilder;
            import dev.vertique.workflow.dsl.WorkflowDefinition;

            public final class RecordedOrder implements WorkflowDefinition<OrderState, OrdersContract> {

                private final String id;
                private final boolean requireStability;

                public RecordedOrder(String id, boolean requireStability) {
                    this.id = id;
                    this.requireStability = requireStability;
                }

                @Override
                public Class<OrdersContract> contract() {
                    return OrdersContract.class;
                }

                @Override
                public Class<OrderState> stateType() {
                    return OrderState.class;
                }

                @Override
                public String definitionId() {
                    return id;
                }

                @Override
                public long definitionVersion() {
                    return 1;
                }

                @Override
                public void define(WorkflowBuilder<OrderState> wf) {
                    wf.init(StartOrder.class, start -> new OrderState(start.id())).initialStep("review");
                    var task = wf.task("review").assignToRole("reviewers");
                    if (requireStability) {
                        task = task.requireVersionStability();
                    }
                    task.decision("approve", Approval.class)
                            .onDecision((state, approval) -> state)
                            .toStep("done")
                            .build();
                    wf.complete("done");
                }
            }
            """;

    private final Path directory;
    private final URLClassLoader loader;

    private RecordingOnlyFixture(Path directory, URLClassLoader loader) {
        this.directory = directory;
        this.loader = loader;
    }

    /** Compiles the fixture and loads it through a class loader of its own, below the test's. */
    static RecordingOnlyFixture compile() {
        Path directory = null;
        URLClassLoader loader = null;
        try {
            directory = Files.createTempDirectory("recording-only-fixture");
            Path classes = Files.createDirectories(directory.resolve("classes"));
            Path sources = Files.createDirectories(directory.resolve("src").resolve(PACKAGE.replace('.', '/')));
            List<Path> files = List.of(
                    write(sources, "OrdersContract", CONTRACT_SOURCE),
                    write(sources, "OrderState", STATE_SOURCE),
                    write(sources, "StartOrder", START_SOURCE),
                    write(sources, "Approval", APPROVAL_SOURCE),
                    write(sources, "RecordedOrder", DEFINITION_SOURCE));
            compileSources(files, classes);
            loader = new URLClassLoader(
                    new URL[] {classes.toUri().toURL()}, RecordingOnlyFixture.class.getClassLoader());
            return new RecordingOnlyFixture(directory, loader);
        } catch (IOException | RuntimeException failure) {
            closeQuietly(loader);
            deleteQuietly(directory);
            if (failure instanceof RuntimeException runtime) throw runtime;
            throw new UncheckedIOException((IOException) failure);
        }
    }

    /** The definition of the id, version 1, created from this fixture's own class loader. */
    WorkflowDefinition<?, ?> definition(String definitionId, boolean requireVersionStability) {
        try {
            return (WorkflowDefinition<?, ?>) load("RecordedOrder")
                    .getConstructor(String.class, boolean.class)
                    .newInstance(definitionId, requireVersionStability);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not create the fixture definition", e);
        }
    }

    /** The type catalog entries of the fixture's payload and state classes, by their binary names. */
    List<WorkflowTypeCatalogEntry> catalog() {
        List<WorkflowTypeCatalogEntry> entries = new ArrayList<>();
        for (String name : List.of("OrderState", "StartOrder", "Approval")) {
            Class<?> type = load(name);
            entries.add(new WorkflowTypeCatalogEntry(Kind.TYPE, type.getName(), type));
        }
        return List.copyOf(entries);
    }

    /** The binary name of a fixture class by its simple name. */
    static String className(String simpleName) {
        return PACKAGE + "." + simpleName;
    }

    private Class<?> load(String simpleName) {
        try {
            return loader.loadClass(className(simpleName));
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("The fixture class " + simpleName + " is missing", e);
        }
    }

    @Override
    public void close() {
        closeQuietly(loader);
        deleteQuietly(directory);
    }

    private static Path write(Path directory, String className, String source) throws IOException {
        Path file = directory.resolve(className + ".java");
        Files.writeString(file, source, StandardCharsets.UTF_8);
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
