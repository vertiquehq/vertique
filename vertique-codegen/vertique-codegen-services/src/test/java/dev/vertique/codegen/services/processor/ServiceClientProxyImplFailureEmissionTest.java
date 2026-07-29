// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.services.processor;

import static dev.vertique.codegen.services.processor.ServiceContractTestFixtures.FRAMEWORK_SOURCES;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.codegen.test.fixtures.SourceFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins the suppression boundary for contract-only client-proxy emission: only a failure rooted in
 * the <em>contract's shape</em> may stop {@link ServiceContractProcessor} from emitting a
 * {@code {Contract}_ServiceClientProxy}.
 *
 * <p>The client proxy is a function of the contract interface alone. A defect in one implementation
 * of that contract — a missing {@code @Inject} constructor, a handler that lacks or overloads a
 * method, an unrecognised extra handler parameter, a double-pattern impl, or two unconditional impls
 * in one group — says nothing about the contract, and the contract-only emission path would not
 * re-report any of those diagnostics. Each such compilation therefore still fails (the impl-side
 * error stands) <em>and</em> still writes the proxy source.
 *
 * <p>The single exception is a genuine contract-shape failure: there, the contract-only path would
 * run the same five validators the impl loop already ran, so emission is suppressed to keep every
 * diagnostic single-reported — the invariant
 * {@code ServiceContractProcessorOperationCollisionTest#collisionError_isReportedExactlyOnce} pins
 * from the other side.
 *
 * <p><strong>Why this class drives {@code javac} directly instead of using
 * {@code ProcessorTestHarness}.</strong> Every scenario here is a <em>failing</em> compilation, and
 * {@code compile-testing}'s {@code Compilation#generatedFiles()} throws
 * {@link IllegalStateException} ("compilation failed, so generated files are unavailable") for any
 * non-successful compilation — so the harness cannot express "failed, and yet this file was
 * emitted". Running {@link JavaCompiler} with a real {@link StandardLocation#SOURCE_OUTPUT}
 * directory observes the emitted file on disk regardless of exit status, which is exactly the fact
 * under test. The compiler options mirror the harness's ({@code --release 21}) and the classpath is
 * the test JVM's, so the framework stubs and real framework classes resolve identically.
 */
class ServiceClientProxyImplFailureEmissionTest {

    // --- Shared fixtures ---

    private static final String USER_SERVICE_PROXY_FQN = "com.example.UserService_ServiceClientProxy";

    private static final JavaFileObject USER_SERVICE_CONTRACT = SourceFiles.inline("com.example.UserService", """
            package com.example;
            import dev.vertique.services.ServiceContract;
            import dev.vertique.services.ServiceOperation;
            import io.vertx.core.Future;
            @ServiceContract(value = "user-service", namespace = "integration")
            public interface UserService {
                @ServiceOperation("get-user")
                Future<String> getUser(String userId);
            }
            """);

    @TempDir
    Path workDir;

    // --- Impl-side failures: compilation fails, the proxy is still emitted ---

    @Test
    @DisplayName("impl without an @Inject constructor still gets its contract's client proxy")
    void missingInjectConstructor_stillEmitsClientProxy() throws IOException {
        JavaFileObject impl = SourceFiles.inline("com.example.UserServiceImpl", """
                package com.example;
                import io.vertx.core.Future;
                public class UserServiceImpl implements UserService {
                    // No @Inject constructor — InjectConstructorValidator rejects this impl
                    public UserServiceImpl() {}
                    @Override public Future<String> getUser(String userId) {
                        return Future.succeededFuture(userId);
                    }
                }
                """);

        Outcome outcome = compile(USER_SERVICE_CONTRACT, impl);

        outcome.assertFailedWith("@Inject constructor");
        outcome.assertGenerated(USER_SERVICE_PROXY_FQN);
    }

    @Test
    @DisplayName("handler missing a contract method still gets its contract's client proxy")
    void handlerMissingMethod_stillEmitsClientProxy() throws IOException {
        JavaFileObject handler = SourceFiles.inline("com.example.UserServiceHandler", """
                package com.example;
                import dev.vertique.services.ServiceHandler;
                import jakarta.inject.Inject;
                import io.vertx.core.Future;
                public class UserServiceHandler implements ServiceHandler<UserService> {
                    @Inject UserServiceHandler() {}
                    // No getUser method — the contract shape is untouched by this defect
                    public Future<String> fetchUser(String userId) {
                        return Future.succeededFuture(userId);
                    }
                }
                """);

        Outcome outcome = compile(USER_SERVICE_CONTRACT, handler);

        outcome.assertFailedWith("does not provide handler method");
        outcome.assertGenerated(USER_SERVICE_PROXY_FQN);
    }

    @Test
    @DisplayName("handler with an unrecognised extra parameter still gets its contract's client proxy")
    void handlerParamMismatch_stillEmitsClientProxy() throws IOException {
        JavaFileObject handler = SourceFiles.inline("com.example.UserServiceHandler", """
                package com.example;
                import dev.vertique.services.ServiceHandler;
                import jakarta.inject.Inject;
                import io.vertx.core.Future;
                public class UserServiceHandler implements ServiceHandler<UserService> {
                    @Inject UserServiceHandler() {}
                    // "extra" is neither a SecurityContext nor @DispatchContextValue-annotated
                    public Future<String> getUser(String userId, String extra) {
                        return Future.succeededFuture(userId);
                    }
                }
                """);

        Outcome outcome = compile(USER_SERVICE_CONTRACT, handler);

        outcome.assertFailedWith("extra parameter 'extra'");
        outcome.assertGenerated(USER_SERVICE_PROXY_FQN);
    }

    @Test
    @DisplayName("handler with an overloaded operation method still gets its contract's client proxy")
    void handlerOverload_stillEmitsClientProxy() throws IOException {
        JavaFileObject handler = SourceFiles.inline("com.example.UserServiceHandler", """
                package com.example;
                import dev.vertique.services.ServiceHandler;
                import jakarta.inject.Inject;
                import io.vertx.core.Future;
                public class UserServiceHandler implements ServiceHandler<UserService> {
                    @Inject UserServiceHandler() {}
                    public Future<String> getUser(String userId) {
                        return Future.succeededFuture(userId);
                    }
                    public Future<String> getUser(Integer userId) {
                        return Future.succeededFuture(userId.toString());
                    }
                }
                """);

        Outcome outcome = compile(USER_SERVICE_CONTRACT, handler);

        outcome.assertFailedWith("overloaded methods named 'getUser'");
        outcome.assertGenerated(USER_SERVICE_PROXY_FQN);
    }

    @Test
    @DisplayName("double-pattern impl still gets its contract's client proxy")
    void doublePattern_stillEmitsClientProxy() throws IOException {
        JavaFileObject impl = SourceFiles.inline("com.example.UserServiceBoth", """
                package com.example;
                import dev.vertique.services.ServiceHandler;
                import jakarta.inject.Inject;
                import io.vertx.core.Future;
                public class UserServiceBoth implements ServiceHandler<UserService>, UserService {
                    @Inject UserServiceBoth() {}
                    @Override public Future<String> getUser(String userId) {
                        return Future.succeededFuture(userId);
                    }
                }
                """);

        Outcome outcome = compile(USER_SERVICE_CONTRACT, impl);

        outcome.assertFailedWith("use one pattern, not both");
        outcome.assertGenerated(USER_SERVICE_PROXY_FQN);
    }

    @Test
    @DisplayName("two unconditional impls in one group still get their contract's client proxy")
    void multipleUnconditionalImpls_stillEmitsClientProxy() throws IOException {
        JavaFileObject implA = SourceFiles.inline("com.example.UserServiceImplA", """
                package com.example;
                import jakarta.inject.Inject;
                import io.vertx.core.Future;
                public class UserServiceImplA implements UserService {
                    @Inject public UserServiceImplA() {}
                    @Override public Future<String> getUser(String userId) {
                        return Future.succeededFuture(userId);
                    }
                }
                """);

        JavaFileObject implB = SourceFiles.inline("com.example.UserServiceImplB", """
                package com.example;
                import jakarta.inject.Inject;
                import io.vertx.core.Future;
                public class UserServiceImplB implements UserService {
                    @Inject public UserServiceImplB() {}
                    @Override public Future<String> getUser(String userId) {
                        return Future.succeededFuture("b-" + userId);
                    }
                }
                """);

        Outcome outcome = compile(USER_SERVICE_CONTRACT, implA, implB);

        outcome.assertFailedWith("multiple unconditional implementations");
        outcome.assertGenerated(USER_SERVICE_PROXY_FQN);
    }

    // --- Contract-shape failure: emission IS suppressed (regression guard) ---

    @Test
    @DisplayName("genuine contract-shape failure suppresses the client proxy")
    void contractShapeFailure_suppressesClientProxy() throws IOException {
        JavaFileObject contract = SourceFiles.inline("com.example.OrderService", """
                package com.example;
                import dev.vertique.services.ServiceContract;
                import dev.vertique.services.ServiceOperation;
                import io.vertx.core.Future;
                @ServiceContract(value = "order-service", namespace = "integration")
                public interface OrderService {
                    @ServiceOperation("foo")
                    Future<String> submitOrder(String id);
                    // Method name "foo" + no annotation → operation "foo" → collision
                    Future<String> foo(String id);
                }
                """);

        JavaFileObject impl = SourceFiles.inline("com.example.OrderServiceImpl", """
                package com.example;
                import jakarta.inject.Inject;
                import io.vertx.core.Future;
                public class OrderServiceImpl implements OrderService {
                    @Inject OrderServiceImpl() {}
                    @Override public Future<String> submitOrder(String id) {
                        return Future.succeededFuture(id);
                    }
                    @Override public Future<String> foo(String id) {
                        return Future.succeededFuture(id);
                    }
                }
                """);

        Outcome outcome = compile(contract, impl);

        outcome.assertFailedWith("Duplicate operation name 'foo' in contract");
        outcome.assertNotGenerated("com.example.OrderService_ServiceClientProxy");
    }

    // --- Compilation helper ---

    /**
     * The outcome of one direct {@code javac} invocation: whether it succeeded, its diagnostics, and
     * the directory the processor's generated sources were written to.
     *
     * @param success     {@code true} when {@code javac} exited successfully
     * @param diagnostics every diagnostic the compilation produced, in order
     * @param sourceOut   the {@link StandardLocation#SOURCE_OUTPUT} directory
     */
    private record Outcome(boolean success, List<Diagnostic<? extends JavaFileObject>> diagnostics, Path sourceOut) {

        /**
         * Asserts the compilation failed and that at least one error names the expected defect.
         *
         * @param expectedError a substring of the impl-side (or contract-shape) error message
         */
        void assertFailedWith(String expectedError) {
            assertFalse(success, "expected the compilation to fail" + summary());
            assertTrue(
                    diagnostics.stream()
                            .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                            .anyMatch(d -> d.getMessage(null) != null
                                    && d.getMessage(null).contains(expectedError)),
                    "expected an error containing '%s'%s".formatted(expectedError, summary()));
        }

        /**
         * Asserts the processor wrote a source file for the given class, despite the failure.
         *
         * @param fqn the fully-qualified name of the expected generated class
         */
        void assertGenerated(String fqn) {
            assertTrue(
                    Files.exists(sourceFileOf(fqn)),
                    "expected '%s' to be emitted despite the impl-side failure%s".formatted(fqn, summary()));
        }

        /**
         * Asserts the processor wrote no source file for the given class.
         *
         * @param fqn the fully-qualified name of the class expected to be absent
         */
        void assertNotGenerated(String fqn) {
            assertFalse(Files.exists(sourceFileOf(fqn)), "expected '%s' NOT to be emitted%s".formatted(fqn, summary()));
        }

        /**
         * Resolves the on-disk path a generated class's source would occupy.
         *
         * @param fqn the fully-qualified class name
         * @return the expected path under {@link #sourceOut()}
         */
        private Path sourceFileOf(String fqn) {
            return sourceOut.resolve(fqn.replace('.', '/') + ".java");
        }

        /**
         * Renders all diagnostics for inclusion in assertion failure messages.
         *
         * @return a human-readable diagnostic dump
         */
        private String summary() {
            StringBuilder sb = new StringBuilder("\nCompilation diagnostics:\n");
            diagnostics.forEach(d -> sb.append("  [")
                    .append(d.getKind())
                    .append("] ")
                    .append(d.getMessage(null))
                    .append('\n'));
            return sb.toString();
        }
    }

    /**
     * Compiles the framework stubs plus the given domain sources through the real processor, writing
     * generated sources to a per-test temporary directory so they can be inspected even when the
     * compilation fails.
     *
     * @param domainSources the domain sources to add on top of
     *                      {@link ServiceContractTestFixtures#FRAMEWORK_SOURCES}
     * @return the compilation outcome
     * @throws IOException if the temporary output directories cannot be prepared
     */
    private Outcome compile(JavaFileObject... domainSources) throws IOException {
        Path sourceOut = Files.createDirectories(workDir.resolve("generated-sources"));
        Path classOut = Files.createDirectories(workDir.resolve("classes"));

        List<JavaFileObject> units = new ArrayList<>(List.of(FRAMEWORK_SOURCES));
        units.addAll(List.of(domainSources));

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();
        boolean success;
        try (StandardJavaFileManager fileManager =
                compiler.getStandardFileManager(collector, null, StandardCharsets.UTF_8)) {
            fileManager.setLocation(StandardLocation.SOURCE_OUTPUT, List.of(sourceOut.toFile()));
            fileManager.setLocation(StandardLocation.CLASS_OUTPUT, List.of(classOut.toFile()));

            List<String> options = List.of("--release", "21", "-classpath", System.getProperty("java.class.path"));
            JavaCompiler.CompilationTask task = compiler.getTask(null, fileManager, collector, options, null, units);
            task.setProcessors(List.of(new ServiceContractProcessor()));
            success = task.call();
        }
        return new Outcome(success, List.copyOf(collector.getDiagnostics()), sourceOut);
    }
}
