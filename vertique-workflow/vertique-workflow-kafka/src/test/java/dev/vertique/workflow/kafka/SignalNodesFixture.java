// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Compiles the workflow that waits for a delivery into a temporary directory that is on no class path and loads it
 * through a class loader of its own, so that only the nodes handed its definition can see its classes.
 *
 * <p>The fixture is a watch over one parcel: a start payload, a state that holds the carrier reference and, once the
 * signal applied, the delivery instant, and a definition that waits for the signal of
 * {@link ShipmentSignals.OrdersContract} correlated on the carrier reference. The contract and the signal's payload are
 * not compiled here: the isolated classes name the ones on the test class path, which every node can see. Every
 * compiled class lives in {@link #PACKAGE}, a package that no class of the test module is in. A copy of these classes
 * under {@code src/test} would be visible to every node and would make the class-load proofs say nothing.
 */
final class SignalNodesFixture implements AutoCloseable {

    /** The package of every compiled class; no class of the test class path is in it. */
    static final String PACKAGE = "fixture.signalnodes";

    private static final String STATE_SOURCE = """
            package fixture.signalnodes;

            import java.time.Instant;

            public record WatchState(String watchId, String carrierRef, Instant deliveredAt) {}
            """;

    private static final String START_SOURCE = """
            package fixture.signalnodes;

            public record WatchStart(String watchId, String carrierRef) {}
            """;

    private static final String DEFINITION_SOURCE = """
            package fixture.signalnodes;

            import dev.vertique.workflow.dsl.WorkflowBuilder;
            import dev.vertique.workflow.dsl.WorkflowDefinition;
            import dev.vertique.workflow.kafka.ShipmentSignals;

            public final class ShipmentWatch implements WorkflowDefinition<WatchState, ShipmentSignals.OrdersContract> {

                private final String id;

                public ShipmentWatch(String id) {
                    this.id = id;
                }

                @Override
                public Class<ShipmentSignals.OrdersContract> contract() {
                    return ShipmentSignals.OrdersContract.class;
                }

                @Override
                public Class<WatchState> stateType() {
                    return WatchState.class;
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
                public void define(WorkflowBuilder<WatchState> wf) {
                    wf.init(WatchStart.class, start -> new WatchState(start.watchId(), start.carrierRef(), null));
                    wf.initialStep("wait-delivery");
                    wf.waitForSignal(
                                    "wait-delivery",
                                    ShipmentSignals.SHIPMENT_DELIVERED,
                                    ShipmentSignals.DeliveryConfirmed.class)
                            .correlateBy(WatchState::carrierRef)
                            .onSignal((state, confirmed) ->
                                    new WatchState(state.watchId(), state.carrierRef(), confirmed.deliveredAt()))
                            .toStepOnSignal("done")
                            .build();
                    wf.complete("done");
                }
            }
            """;

    private final Path directory;
    private final URLClassLoader loader;

    private SignalNodesFixture(Path directory, URLClassLoader loader) {
        this.directory = directory;
        this.loader = loader;
    }

    /** Compiles the fixture and loads it through a class loader of its own, below the test's. */
    static SignalNodesFixture compile() {
        Path directory = null;
        URLClassLoader loader = null;
        try {
            directory = Files.createTempDirectory("signal-nodes-fixture");
            Path classes = Files.createDirectories(directory.resolve("classes"));
            Path sources = Files.createDirectories(directory.resolve("src").resolve(PACKAGE.replace('.', '/')));
            List<Path> files = List.of(
                    write(sources, "WatchState", STATE_SOURCE),
                    write(sources, "WatchStart", START_SOURCE),
                    write(sources, "ShipmentWatch", DEFINITION_SOURCE));
            compileSources(files, classes);
            loader = new URLClassLoader(new URL[] {classes.toUri().toURL()}, SignalNodesFixture.class.getClassLoader());
            return new SignalNodesFixture(directory, loader);
        } catch (IOException | RuntimeException failure) {
            closeQuietly(loader);
            deleteQuietly(directory);
            if (failure instanceof RuntimeException runtime) throw runtime;
            throw new UncheckedIOException((IOException) failure);
        }
    }

    /** The binary name of a fixture class by its simple name. */
    static String className(String simpleName) {
        return PACKAGE + "." + simpleName;
    }

    /** Whether the loader can resolve the fixture's definition class by name, without initializing it. */
    static boolean visibleTo(ClassLoader loader) {
        try {
            Class.forName(className("ShipmentWatch"), false, loader);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /** The class loader of the fixture, which the executor node's definition comes from. */
    ClassLoader loader() {
        return loader;
    }

    /** The definition of the id, version 1, created from this fixture's own class loader. */
    WorkflowDefinition<?, ?> definition(String definitionId) {
        try {
            return (WorkflowDefinition<?, ?>)
                    load("ShipmentWatch").getConstructor(String.class).newInstance(definitionId);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not create the fixture definition", e);
        }
    }

    /** The start payload of an instance that waits for a delivery under the carrier reference. */
    Map<String, Object> start(String watchId, String carrierRef) {
        return Map.of("watchId", watchId, "carrierRef", carrierRef);
    }

    /** Decodes a stored state into the fixture's state class with the mapper and reads its delivery instant. */
    Instant deliveredAt(ObjectMapper mapper, String stateJson) {
        try {
            Object state = mapper.readValue(stateJson, load("WatchState"));
            return (Instant) state.getClass().getMethod("deliveredAt").invoke(state);
        } catch (IOException | ReflectiveOperationException e) {
            throw new AssertionError("the stored state does not decode into the fixture's state class", e);
        }
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

    /** Whether the directory that held the compiled classes is gone. */
    boolean removed() {
        return !Files.exists(directory);
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
