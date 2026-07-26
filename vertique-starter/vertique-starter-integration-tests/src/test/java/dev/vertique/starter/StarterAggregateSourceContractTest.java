// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.starter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;

/**
 * Source-syntax contract test over every starter aggregate module in the reactor.
 *
 * <p>Aggregates must declare no explicit constructor: they are named by downstream Dagger
 * {@code @Component} declarations, never instantiated. Java emits an implicit public no-arg
 * constructor for every public class, so reflection cannot tell an implicit constructor from an
 * explicitly declared one. This test therefore parses each aggregate's <em>source</em> through the
 * JDK compiler syntax-tree API, where only explicitly written constructors appear.
 *
 * <p>The aggregate set is discovered dynamically from the outer reactor root, so starters added
 * later are covered without editing this test.
 */
class StarterAggregateSourceContractTest {

    /** Reactor-relative directory holding the starter family. */
    private static final String STARTER_FAMILY_DIRECTORY = "vertique-starter";

    /** Prefix every starter family child directory carries. */
    private static final String STARTER_MODULE_PREFIX = "vertique-starter-";

    /** Module-relative production source root scanned for aggregates. */
    private static final String PRODUCTION_SOURCE_ROOT = "src/main/java";

    /** File-name suffix identifying a Dagger aggregate module source. */
    private static final String AGGREGATE_SOURCE_SUFFIX = "Module.java";

    @Test
    void aggregatesDeclareNoExplicitConstructors() throws IOException {
        Path reactorRoot = ReactorRootLocator.locate();
        List<Path> aggregateSources = aggregateSources(reactorRoot);

        assertFalse(
                aggregateSources.isEmpty(),
                "Found no starter aggregate sources under " + reactorRoot.resolve(STARTER_FAMILY_DIRECTORY)
                        + "/" + STARTER_MODULE_PREFIX + "*/" + PRODUCTION_SOURCE_ROOT
                        + " — the discovery glob is wrong or the family moved");

        List<String> violations = explicitConstructors(aggregateSources);
        assertTrue(
                violations.isEmpty(), "Starter aggregates must declare no explicit constructor, found: " + violations);
    }

    // --- Discovery ---

    /**
     * Collects every aggregate module source under the starter family's production source roots.
     *
     * @param reactorRoot the reactor root directory; must not be {@code null}
     * @return the aggregate source files, sorted for deterministic reporting
     * @throws IOException when the starter family cannot be listed
     */
    private static List<Path> aggregateSources(Path reactorRoot) throws IOException {
        Path family = reactorRoot.resolve(STARTER_FAMILY_DIRECTORY);
        if (!Files.isDirectory(family)) {
            return List.of();
        }
        try (Stream<Path> children = Files.list(family)) {
            return children.filter(Files::isDirectory)
                    .filter(child -> child.getFileName().toString().startsWith(STARTER_MODULE_PREFIX))
                    .map(child -> child.resolve(PRODUCTION_SOURCE_ROOT))
                    .filter(Files::isDirectory)
                    .flatMap(StarterAggregateSourceContractTest::moduleSourcesIn)
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
    }

    /**
     * Streams the {@code *Module.java} sources beneath one production source root.
     *
     * @param sourceRoot the module's {@code src/main/java} directory; must not be {@code null}
     * @return the aggregate module sources found beneath it
     */
    private static Stream<Path> moduleSourcesIn(Path sourceRoot) {
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().endsWith(AGGREGATE_SOURCE_SUFFIX))
                    .toList()
                    .stream();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to scan " + sourceRoot, e);
        }
    }

    // --- Source-syntax analysis ---

    /**
     * Parses each source through the JDK compiler syntax-tree API and reports every explicitly
     * declared constructor as {@code <file>:<class>}.
     *
     * @param sources the aggregate sources to parse; must not be {@code null}
     * @return the explicit-constructor violations, empty when the contract holds
     * @throws IOException when the compiler's file manager cannot be closed
     */
    private static List<String> explicitConstructors(List<Path> sources) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "A JDK with the system Java compiler is required to parse aggregate sources");

        List<File> files = sources.stream().map(Path::toFile).toList();
        List<String> violations = new ArrayList<>();
        try (StandardJavaFileManager fileManager =
                compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> units = fileManager.getJavaFileObjectsFromFiles(files);
            JavacTask task = (JavacTask) compiler.getTask(null, fileManager, diagnostic -> {}, List.of(), null, units);
            for (CompilationUnitTree unit : task.parse()) {
                String name =
                        Path.of(unit.getSourceFile().toUri()).getFileName().toString();
                ExplicitConstructorScanner scanner = new ExplicitConstructorScanner(name);
                scanner.scan(unit, null);
                violations.addAll(scanner.violations());
            }
        }
        return violations;
    }

    /** Collects explicitly declared constructors ({@code <init>} method trees) from parsed sources. */
    private static final class ExplicitConstructorScanner extends TreeScanner<Void, String> {

        private final String sourceName;
        private final List<String> violations = new ArrayList<>();

        /**
         * Creates a scanner reporting violations against the given source file name.
         *
         * @param sourceName the parsed source's file name; must not be {@code null}
         */
        ExplicitConstructorScanner(String sourceName) {
            this.sourceName = sourceName;
        }

        @Override
        public Void visitClass(ClassTree node, String enclosingClass) {
            return super.visitClass(node, node.getSimpleName().toString());
        }

        @Override
        public Void visitMethod(MethodTree node, String enclosingClass) {
            if (node.getName().contentEquals("<init>")) {
                violations.add(sourceName + ":" + enclosingClass);
            }
            return super.visitMethod(node, enclosingClass);
        }

        /**
         * Returns the explicit constructors found so far.
         *
         * @return the violations, empty when none were declared
         */
        List<String> violations() {
            return violations;
        }
    }
}
