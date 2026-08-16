// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.test;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.processing.Processor;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import org.opentest4j.AssertionFailedError;

/**
 * Fluent test harness for annotation processors built on
 * {@link com.google.testing.compile.Compiler}.
 *
 * <p>Wraps {@code compile-testing}'s {@link Compiler#javac()} and exposes a {@link Result} class
 * with assertion methods that throw {@link AssertionFailedError} (from {@code opentest4j}) rather
 * than JUnit-specific types. This makes the harness compatible with JUnit 5 (where opentest4j is
 * already the assertion foundation), JUnit 4, and any other framework that integrates opentest4j.
 *
 * <p>Usage:
 * <pre>{@code
 * @Test
 * void myProcessorGeneratesModule() {
 *     ProcessorTestHarness.run(new MyProcessor(), SourceFiles.inline("com.example.Foo", """
 *             package com.example;
 *             public class Foo {}
 *             """))
 *         .assertSuccess()
 *         .assertGeneratedSourceContains("com.example.FooModule", "@Module");
 * }
 * }</pre>
 *
 * <p>All assertion failures include the full compilation diagnostics in the failure message so
 * downstream tests get actionable output.
 */
public final class ProcessorTestHarness {

    /**
     * Default {@code javac} options applied to every compilation. Pinning {@code --release} to the
     * project's target Java version makes the harness deterministic across host JDKs: without
     * this, a JDK newer than the project's release would default to its own newer release and
     * cause processors annotated with {@code @SupportedSourceVersion(RELEASE_21)} to emit
     * source-version-mismatch warnings — which {@link Result#assertNoWarnings()} would then
     * surface as test failures.
     */
    private static final List<String> BASE_JAVAC_OPTIONS = List.of("--release", "21");

    private ProcessorTestHarness() {}

    /**
     * Compiles the given source files using a single annotation processor.
     *
     * @param processor the processor to run; must not be {@code null}
     * @param sources   the source files to compile; must not be empty
     * @return a {@link Result} wrapping the compilation outcome
     */
    public static Result run(Processor processor, JavaFileObject... sources) {
        Compilation compilation = Compiler.javac()
                .withProcessors(processor)
                .withOptions(BASE_JAVAC_OPTIONS)
                .compile(sources);
        return new Result(compilation);
    }

    /**
     * Compiles the given source files using a collection of annotation processors.
     *
     * @param processors the processors to run; must not be {@code null} or empty
     * @param sources    the source files to compile; must not be empty
     * @return a {@link Result} wrapping the compilation outcome
     */
    public static Result run(Iterable<Processor> processors, JavaFileObject... sources) {
        Compilation compilation = Compiler.javac()
                .withProcessors(processors)
                .withOptions(BASE_JAVAC_OPTIONS)
                .compile(sources);
        return new Result(compilation);
    }

    /**
     * Compiles the given source files using a single annotation processor with additional
     * {@code -A} processor options.
     *
     * <p>Each map entry is converted to a {@code -Akey=value} compiler option, appended to the
     * harness's pinned {@code --release} options.
     *
     * @param processor       the processor to run; must not be {@code null}
     * @param processorOptions additional {@code -A} options passed to the processor; must not be
     *                         {@code null} (pass an empty map for none)
     * @param sources          the source files to compile; must not be empty
     * @return a {@link Result} wrapping the compilation outcome
     */
    public static Result run(Processor processor, Map<String, String> processorOptions, JavaFileObject... sources) {
        List<String> options = new ArrayList<>(BASE_JAVAC_OPTIONS);
        processorOptions.entrySet().stream()
                .map(e -> "-A" + e.getKey() + "=" + e.getValue())
                .forEach(options::add);

        Compilation compilation =
                Compiler.javac().withProcessors(processor).withOptions(options).compile(sources);
        return new Result(compilation);
    }

    /**
     * Compiles the given source files using a collection of annotation processors with additional
     * {@code -A} processor options.
     *
     * <p>Each map entry is converted to a {@code -Akey=value} compiler option, appended to the
     * harness's pinned {@code --release} options. All processors in the collection receive the
     * same options; this mirrors the behaviour of real annotation-processor tooling where
     * {@code -A} flags are broadcast to every active processor.
     *
     * @param processors      the processors to run; must not be {@code null} or empty
     * @param processorOptions additional {@code -A} options passed to all processors; must not be
     *                         {@code null} (pass an empty map for none)
     * @param sources          the source files to compile; must not be empty
     * @return a {@link Result} wrapping the compilation outcome
     */
    public static Result run(
            Iterable<Processor> processors, Map<String, String> processorOptions, JavaFileObject... sources) {
        List<String> options = new ArrayList<>(BASE_JAVAC_OPTIONS);
        processorOptions.entrySet().stream()
                .map(e -> "-A" + e.getKey() + "=" + e.getValue())
                .forEach(options::add);

        Compilation compilation =
                Compiler.javac().withProcessors(processors).withOptions(options).compile(sources);
        return new Result(compilation);
    }

    // --- Result class ---

    /**
     * The outcome of a {@link ProcessorTestHarness#run} invocation.
     *
     * <p>All assertion methods return {@code this} so they can be chained fluently. Each failing
     * assertion throws {@link AssertionFailedError} with the full compilation diagnostics
     * appended to the failure message.
     *
     * <p>The {@link #generatedClassLoader()} and {@link #loadGeneratedClass(String)} methods allow
     * tests to load and instantiate classes produced by the compilation — both processor-generated
     * classes and user-supplied source fixture classes compiled during the same invocation. The
     * classloader is built lazily on first call and reused; all classes are defined once so
     * repeated calls to {@code loadGeneratedClass} on the same {@code Result} instance are safe.
     *
     * <p>Note: {@code Result} was a {@code record} through codegen-test 0.1. It was converted to
     * a {@code final class} to allow memoized classloader state. All existing public API
     * ({@link #compilation()}, all {@code assert*} methods) is preserved unchanged.
     */
    public static final class Result {

        private final Compilation compilation;

        /**
         * Lazily initialised classloader. Guarded by {@code this}; {@code null} until first call
         * to {@link #generatedClassLoader()}.
         */
        private volatile ClassLoader classLoader;

        /**
         * The set of FQNs whose bytecode was actually produced by this compilation. Populated
         * alongside {@link #classLoader} on first use. Used by {@link #loadGeneratedClass(String)}
         * to reject FQNs that exist on the parent classpath but were NOT produced by this
         * compilation — without this guard, the child-first loader would silently fall back to
         * the parent and return the wrong class.
         */
        private volatile Set<String> producedFqns;

        /**
         * Creates a result wrapping the given compilation.
         *
         * @param compilation the raw {@link Compilation} produced by {@code compile-testing}
         */
        Result(Compilation compilation) {
            this.compilation = compilation;
        }

        /**
         * Returns the raw {@link Compilation} produced by {@code compile-testing}.
         *
         * @return the compilation
         */
        public Compilation compilation() {
            return compilation;
        }

        // --- Classloader API ---

        /**
         * Returns a ClassLoader that can load any class produced by this compilation —
         * both classes generated by the annotation processor and classes compiled from
         * user-supplied source fixtures. Unknown classes delegate to the test classpath.
         *
         * <p>The classloader is built lazily on first call and reused thereafter. All class
         * bytes are read from {@link Compilation#generatedFiles()} filtered to
         * {@link JavaFileObject.Kind#CLASS}. The FQN is parsed from the URI path
         * ({@code /CLASS_OUTPUT/com/example/Foo.class} → {@code com.example.Foo}).
         *
         * <p>The parent classloader is {@code Result.class.getClassLoader()} so that framework
         * types on the test classpath (e.g. {@code ServiceContractContributor}) are resolved
         * to the same class objects that the calling test code uses, preserving type identity.
         *
         * <p><strong>Child-first delegation:</strong> the returned loader uses child-first
         * delegation — in-memory compiled bytes win over the parent classpath when an FQN exists
         * on both sides. Platform classes ({@code java.*}, {@code javax.*}, {@code jdk.*}) are
         * always loaded by the parent to preserve JVM identity.
         *
         * @return a ClassLoader that resolves compilation outputs first, then the parent
         * @throws AssertionFailedError if the compilation did not succeed
         */
        public ClassLoader generatedClassLoader() {
            assertSuccess();
            ClassLoader loader = this.classLoader;
            if (loader == null) {
                synchronized (this) {
                    loader = this.classLoader;
                    if (loader == null) {
                        loader = buildClassLoader();
                        this.classLoader = loader;
                    }
                }
            }
            return loader;
        }

        /**
         * Loads a class produced by this compilation by fully-qualified name.
         *
         * <p>This resolves both processor-generated classes and classes compiled from
         * user-supplied {@link dev.vertique.codegen.test.fixtures.SourceFiles#inline inline}
         * fixtures. The underlying classloader is shared and memoised across calls so that
         * the same {@code Class} object is returned every time for the same FQN — calling
         * this method twice for the same name is safe.
         *
         * <p>The contract is strict: the FQN must be present in
         * {@link Compilation#generatedFiles()} as a {@link JavaFileObject.Kind#CLASS} entry.
         * If the FQN happens to exist on the parent (test) classpath but was NOT produced by
         * this compilation, this method throws {@link AssertionFailedError} rather than
         * silently returning the parent's class. This protects test code that wants to verify
         * "the processor really emitted X" from accidentally loading an unrelated namesake.
         *
         * <p>For matching FQNs, child-first delegation guarantees the in-memory compiled bytes
         * win over the parent classpath.
         *
         * @param fqn fully-qualified class name (e.g.
         *            {@code "com.example.UserService_ContractContributor"})
         * @return the loaded {@link Class}
         * @throws AssertionFailedError if no class with that FQN was produced by the compilation
         */
        public Class<?> loadGeneratedClass(String fqn) {
            ClassLoader loader = generatedClassLoader();
            if (!producedFqns.contains(fqn)) {
                throw new AssertionFailedError("No class '%s' was produced by this compilation.%s%s"
                        .formatted(fqn, producedFqnsSummary(), diagnosticSummary()));
            }
            try {
                return loader.loadClass(fqn);
            } catch (ClassNotFoundException e) {
                // Should be unreachable: we just verified producedFqns contains the FQN, so the
                // child-first loader must serve it from in-memory bytes. Surface as a harness bug.
                throw new AssertionFailedError(("Internal harness bug: FQN '%s' is in producedFqns but"
                                        + " the classloader could not load it: %s")
                                .formatted(fqn, e.getMessage())
                        + diagnosticSummary());
            }
        }

        // --- Assertion methods ---

        /**
         * Asserts that the compilation succeeded (status {@link Compilation.Status#SUCCESS}).
         *
         * @return this result for chaining
         * @throws AssertionFailedError if the compilation did not succeed
         */
        public Result assertSuccess() {
            if (compilation.status() != Compilation.Status.SUCCESS) {
                throw new AssertionFailedError("Expected compilation to succeed but it failed." + diagnosticSummary());
            }
            return this;
        }

        /**
         * Asserts that the compilation failed (status {@link Compilation.Status#FAILURE}).
         *
         * @return this result for chaining
         * @throws AssertionFailedError if the compilation did not fail
         */
        public Result assertFailed() {
            if (compilation.status() != Compilation.Status.FAILURE) {
                throw new AssertionFailedError("Expected compilation to fail but it succeeded." + diagnosticSummary());
            }
            return this;
        }

        /**
         * Asserts that the compilation generated a source file with the given fully-qualified class
         * name, and that the file's content contains the given snippet.
         *
         * @param generatedFqn the fully-qualified name of the expected generated class (e.g.,
         *                     {@code "com.example.FooModule"})
         * @param snippet      a substring expected to appear in the generated source; must not be
         *                     {@code null}
         * @return this result for chaining
         * @throws AssertionFailedError if the source file is not generated or does not contain the
         *                              snippet
         */
        public Result assertGeneratedSourceContains(String generatedFqn, String snippet) {
            var generated = compilation.generatedSourceFile(generatedFqn);
            if (generated.isEmpty()) {
                throw new AssertionFailedError(
                        "Expected generated source file for '%s' but none was found.".formatted(generatedFqn)
                                + diagnosticSummary());
            }

            String content;
            try {
                content = generated.get().getCharContent(true).toString();
            } catch (IOException e) {
                throw new AssertionFailedError(
                        "Failed to read generated source for '%s': %s".formatted(generatedFqn, e.getMessage()));
            }

            if (!content.contains(snippet)) {
                throw new AssertionFailedError(("Generated source for '%s' does not contain expected snippet.\n"
                                + "Expected snippet: %s\n"
                                + "Actual content:\n%s")
                        .formatted(generatedFqn, snippet, content));
            }
            return this;
        }

        /**
         * Asserts that the compilation generated a source file with the given fully-qualified class
         * name, and that the file's content does NOT contain the given snippet.
         *
         * <p>This is the negative counterpart to {@link #assertGeneratedSourceContains(String, String)}.
         * It is useful for verifying that the emitter produces an erased type literal (e.g.
         * {@code Envelope.class}) and does NOT produce the uncompilable parameterized form
         * (e.g. {@code Envelope<OrderEvent>.class}).
         *
         * @param generatedFqn the fully-qualified name of the expected generated class
         * @param snippet      a substring expected to be absent from the generated source; must not
         *                     be {@code null}
         * @return this result for chaining
         * @throws AssertionFailedError if the source file is not generated or contains the snippet
         */
        public Result assertGeneratedSourceDoesNotContain(String generatedFqn, String snippet) {
            var generated = compilation.generatedSourceFile(generatedFqn);
            if (generated.isEmpty()) {
                throw new AssertionFailedError(
                        "Expected generated source file for '%s' but none was found.".formatted(generatedFqn)
                                + diagnosticSummary());
            }

            String content;
            try {
                content = generated.get().getCharContent(true).toString();
            } catch (IOException e) {
                throw new AssertionFailedError(
                        "Failed to read generated source for '%s': %s".formatted(generatedFqn, e.getMessage()));
            }

            if (content.contains(snippet)) {
                throw new AssertionFailedError(("Generated source for '%s' must NOT contain snippet but it does.\n"
                                + "Forbidden snippet: %s\n"
                                + "Actual content:\n%s")
                        .formatted(generatedFqn, snippet, content));
            }
            return this;
        }

        /**
         * Asserts that at least one compiler diagnostic of kind {@link Diagnostic.Kind#ERROR}
         * has a message containing the given substring.
         *
         * @param substring the expected substring in at least one error diagnostic message; must
         *                  not be {@code null}
         * @return this result for chaining
         * @throws AssertionFailedError if no error diagnostic contains the given substring
         */
        public Result assertErrorMessage(String substring) {
            boolean found = compilation.diagnostics().stream()
                    .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                    .map(d -> d.getMessage(null))
                    .anyMatch(msg -> msg != null && msg.contains(substring));

            if (!found) {
                throw new AssertionFailedError(
                        "Expected an error diagnostic containing '%s' but none was found.".formatted(substring)
                                + diagnosticSummary());
            }
            return this;
        }

        /**
         * Asserts that at least one diagnostic of kind {@link Diagnostic.Kind#WARNING} or
         * {@link Diagnostic.Kind#MANDATORY_WARNING} has a message containing the given substring.
         *
         * <p>Use this when a warning is the user-facing contract for a deliberate degradation —
         * for example an emitter that skips a binding it cannot legally write. Asserting only that
         * the output is absent leaves the explanation untested, so silently dropping the diagnostic
         * would keep such a test green.
         *
         * @param substring the expected substring in at least one warning diagnostic message; must
         *                  not be {@code null}
         * @return this result for chaining
         * @throws AssertionFailedError if no warning diagnostic contains the given substring
         */
        public Result assertWarningMessage(String substring) {
            boolean found = compilation.diagnostics().stream()
                    .filter(d ->
                            d.getKind() == Diagnostic.Kind.WARNING || d.getKind() == Diagnostic.Kind.MANDATORY_WARNING)
                    .map(d -> d.getMessage(null))
                    .anyMatch(msg -> msg != null && msg.contains(substring));

            if (!found) {
                throw new AssertionFailedError(
                        "Expected a warning diagnostic containing '%s' but none was found.".formatted(substring)
                                + diagnosticSummary());
            }
            return this;
        }

        /**
         * Asserts that the compilation produced no diagnostics of kind
         * {@link Diagnostic.Kind#WARNING} or {@link Diagnostic.Kind#MANDATORY_WARNING}.
         *
         * @return this result for chaining
         * @throws AssertionFailedError if any warning diagnostics were produced
         */
        public Result assertNoWarnings() {
            List<Diagnostic<? extends JavaFileObject>> warnings = compilation.diagnostics().stream()
                    .filter(d ->
                            d.getKind() == Diagnostic.Kind.WARNING || d.getKind() == Diagnostic.Kind.MANDATORY_WARNING)
                    .collect(Collectors.toList());

            if (!warnings.isEmpty()) {
                throw new AssertionFailedError(
                        "Expected no warnings but found " + warnings.size() + " warning(s)." + diagnosticSummary());
            }
            return this;
        }

        // --- Internal helpers ---

        /**
         * Builds a classloader from the CLASS-kind files in {@link Compilation#generatedFiles()}.
         *
         * <p>Iterates every {@link JavaFileObject.Kind#CLASS} file in the compilation output,
         * parses its FQN from the URI path (pattern:
         * {@code mem:///CLASS_OUTPUT/com/example/Foo.class}), reads the raw bytes, and stores
         * them in a map. The resulting {@link ClassLoader} overrides {@link ClassLoader#findClass}
         * to serve bytes from that map before delegating to the parent (test-classpath) loader.
         *
         * @return the constructed classloader
         */
        private ClassLoader buildClassLoader() {
            Map<String, byte[]> classBytesByFqn = new HashMap<>();

            for (JavaFileObject file : compilation.generatedFiles()) {
                if (file.getKind() != JavaFileObject.Kind.CLASS) {
                    continue;
                }
                URI uri = file.toUri();
                String path = uri.getPath(); // e.g. /CLASS_OUTPUT/com/example/Foo.class
                String fqn = extractFqn(path);
                if (fqn == null) {
                    continue;
                }
                try (InputStream is = file.openInputStream()) {
                    classBytesByFqn.put(fqn, is.readAllBytes());
                } catch (IOException e) {
                    // Skip unreadable entries — they won't be loadable, but
                    // that's fine: the caller will get a ClassNotFoundException.
                }
            }

            // Snapshot the produced FQNs so loadGeneratedClass can reject names that exist on
            // the parent classpath but were never produced by this compilation.
            this.producedFqns = Set.copyOf(classBytesByFqn.keySet());

            ClassLoader parent = Result.class.getClassLoader();
            return new GeneratedClassLoader(parent, classBytesByFqn);
        }

        /**
         * Parses the fully-qualified class name from a {@code mem://} URI path segment.
         *
         * <p>Expected format: {@code /CLASS_OUTPUT/com/example/Foo.class} (or any location
         * name prefix followed by a slash). The method strips the location prefix and the
         * {@code .class} suffix and converts {@code /} separators to {@code .}.
         *
         * @param uriPath the path component from a {@code mem://} URI
         * @return the FQN, or {@code null} if the path does not match the expected pattern
         */
        private static String extractFqn(String uriPath) {
            if (uriPath == null || !uriPath.endsWith(".class")) {
                return null;
            }
            // Strip leading slash and find the first slash that follows the location segment
            String withoutLeading = uriPath.startsWith("/") ? uriPath.substring(1) : uriPath;
            int slashAfterLocation = withoutLeading.indexOf('/');
            if (slashAfterLocation < 0) {
                return null;
            }
            // e.g. "com/example/Foo.class"
            String classRelative = withoutLeading.substring(slashAfterLocation + 1);
            // Strip .class suffix and replace / with .
            String withoutExtension = classRelative.substring(0, classRelative.length() - ".class".length());
            return withoutExtension.replace('/', '.');
        }

        /**
         * Builds a sorted summary of FQNs produced by the compilation for inclusion in
         * {@link #loadGeneratedClass(String)} failure messages.
         *
         * @return a human-readable list, or a "(none)" placeholder when nothing was produced;
         *     callable only after {@link #generatedClassLoader()} has initialised
         *     {@link #producedFqns}
         */
        private String producedFqnsSummary() {
            var sb = new StringBuilder("\nProduced classes:\n");
            if (producedFqns.isEmpty()) {
                sb.append("  (none)\n");
                return sb.toString();
            }
            producedFqns.stream()
                    .sorted()
                    .forEach(fqn -> sb.append("  ").append(fqn).append('\n'));
            return sb.toString();
        }

        /**
         * Builds a diagnostic summary string from all compilation diagnostics for inclusion in
         * assertion failure messages.
         *
         * @return a human-readable summary of all diagnostics
         */
        private String diagnosticSummary() {
            var sb = new StringBuilder("\nCompilation diagnostics:\n");
            compilation.diagnostics().forEach(d -> sb.append("  [")
                    .append(d.getKind())
                    .append("] ")
                    .append(d.getMessage(null))
                    .append('\n'));
            return sb.toString();
        }

        // --- Inner class ---

        /**
         * Custom classloader that serves class bytes collected from a compilation's output files.
         *
         * <p>Classes whose FQN is present in the supplied byte map are defined immediately via
         * {@link ClassLoader#defineClass}. All other names delegate to the parent classloader
         * (the test classpath) so that framework types shared between the generated code and
         * the test assertions resolve to the same {@link Class} instance.
         *
         * <p><strong>Child-first delegation:</strong> {@link #loadClass(String, boolean)} is
         * overridden to consult the in-memory byte map before the parent classloader. This
         * ensures that when the same FQN exists both in the compiled output and on the parent
         * (test) classpath, the freshly-compiled bytes win. Platform classes ({@code java.*},
         * {@code javax.*}, {@code jdk.*}) are always delegated to the parent first to preserve
         * JVM type identity.
         */
        private static final class GeneratedClassLoader extends ClassLoader {

            private final Map<String, byte[]> classBytesByFqn;

            /**
             * Creates a classloader backed by the given byte map.
             *
             * @param parent          the parent classloader (typically the test classpath loader)
             * @param classBytesByFqn map from FQN to raw {@code .class} bytes
             */
            GeneratedClassLoader(ClassLoader parent, Map<String, byte[]> classBytesByFqn) {
                super(parent);
                this.classBytesByFqn = classBytesByFqn;
            }

            /**
             * Child-first class loading: consults the in-memory byte map before the parent
             * classloader. Platform classes ({@code java.*}, {@code javax.*}, {@code jdk.*}) are
             * always delegated to the parent first so that JVM-managed types are never shadowed.
             *
             * @param name    the binary class name
             * @param resolve whether to resolve the loaded class
             * @return the loaded {@link Class}
             * @throws ClassNotFoundException if the class cannot be found in the byte map or the
             *                                parent classloader
             */
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                synchronized (getClassLoadingLock(name)) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null) {
                        // Platform / JDK classes must always come from the parent — never override.
                        if (name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("jdk.")) {
                            loaded = super.loadClass(name, false);
                        } else {
                            // Child-first: try our compiled bytes, fall back to the parent.
                            try {
                                loaded = findClass(name);
                            } catch (ClassNotFoundException notLocal) {
                                loaded = super.loadClass(name, false);
                            }
                        }
                    }
                    if (resolve) {
                        resolveClass(loaded);
                    }
                    return loaded;
                }
            }

            /**
             * Attempts to define a class from the compilation's byte map before delegating to
             * the parent. Inner classes (names containing {@code $}) are also supported because
             * their bytes are stored under the full binary name with the {@code $} separator.
             *
             * @param name the binary class name
             * @return the defined {@link Class}
             * @throws ClassNotFoundException if neither the byte map nor the parent knows this name
             */
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = classBytesByFqn.get(name);
                if (bytes != null) {
                    return defineClass(name, bytes, 0, bytes.length);
                }
                throw new ClassNotFoundException(name);
            }
        }
    }
}
