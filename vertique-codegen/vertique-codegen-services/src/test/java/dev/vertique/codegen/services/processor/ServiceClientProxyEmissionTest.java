// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.services.processor;

import static dev.vertique.codegen.services.processor.ServiceContractTestFixtures.FRAMEWORK_SOURCES;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.ProcessorTestHarness.Result;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RED tests (CG-015 Slice S1) for the not-yet-implemented service client static-proxy emission.
 *
 * <p>Pins the emission contract frozen by the CG-015 plan (§4.1, §4.2 mismatch-message protocol,
 * §4.4) for {@code {Contract}_ServiceClientProxy} classes that {@link ServiceContractProcessor}'s
 * planned contract-only discovery loop will generate for every source-root
 * {@code @ServiceContract} interface: minimal direct emission, all four dispatch shapes
 * (security-context parameter, {@code @OneWay}, inherited, and default methods), nested-contract
 * name flattening, emission through a substituted generic super-interface, the
 * generic-contract/generic-method skip-with-NOTE paths, static-method exclusion, selectivity
 * against unannotated interfaces, and the pinned mismatch-message prefix baked into the generated
 * constructor.
 *
 * <p><b>Every test in this class currently fails.</b> Neither the contract-only discovery loop nor
 * {@code ClientProxyEmitter} exists yet — {@link ServiceContractProcessor} presently emits only
 * {@code {Contract}_ContractContributor} sources for concrete implementations found in the
 * compilation unit, and silently ignores contract-only interfaces (they are skipped by
 * {@code ImplCandidateScanner} as neither interfaces nor abstract classes are impl candidates).
 */
class ServiceClientProxyEmissionTest {

    @Test
    @DisplayName("minimal direct contract emits a proxy implementing the contract with no reflection import")
    void emitsProxyForMinimalDirectContract() {
        JavaFileObject contract = SourceFiles.inline("com.example.Greeter", """
                package com.example;
                import dev.vertique.services.ServiceContract;
                import dev.vertique.services.ServiceOperation;
                import io.vertx.core.Future;
                @ServiceContract("greeter")
                public interface Greeter {
                    @ServiceOperation("greet")
                    Future<String> greet(String name);
                }
                """);

        JavaFileObject[] sources = concat(FRAMEWORK_SOURCES, contract);
        var result = ProcessorTestHarness.run(new ServiceContractProcessor(), sources);

        result.assertSuccess();

        String proxyFqn = "com.example.Greeter_ServiceClientProxy";
        result.assertGeneratedSourceContains(proxyFqn, "implements Greeter");
        result.assertGeneratedSourceContains(proxyFqn, "public final class Greeter_ServiceClientProxy");
        result.assertGeneratedSourceDoesNotContain(proxyFqn, "java.lang.reflect");
    }

    @Test
    @DisplayName("emits overrides for every dispatch shape: SC param, one-way, inherited, and default methods")
    void emitsAllDispatchShapes() {
        JavaFileObject base = SourceFiles.inline("com.example.DispatchShapesBase", """
                package com.example;
                import dev.vertique.services.ServiceOperation;
                import io.vertx.core.Future;
                public interface DispatchShapesBase {
                    @ServiceOperation("inherited-op")
                    Future<String> inheritedOp(String value);
                }
                """);

        JavaFileObject contract = SourceFiles.inline("com.example.DispatchShapes", """
                package com.example;
                import dev.vertique.services.ServiceContract;
                import dev.vertique.services.ServiceOperation;
                import dev.vertique.services.OneWay;
                import dev.vertique.security.SecurityContext;
                import io.vertx.core.Future;
                @ServiceContract("dispatch-shapes")
                public interface DispatchShapes extends DispatchShapesBase {
                    @ServiceOperation("with-sc")
                    Future<String> withSecurityContext(String payload, SecurityContext sc);

                    @OneWay
                    @ServiceOperation("notify-dispatch")
                    Future<Void> notifyDispatch(String payload);

                    @ServiceOperation("default-op")
                    default Future<String> defaultOp(String value) {
                        throw new UnsupportedOperationException("default body must not execute");
                    }
                }
                """);

        JavaFileObject[] sources = concat(FRAMEWORK_SOURCES, base, contract);
        var result = ProcessorTestHarness.run(new ServiceContractProcessor(), sources);

        result.assertSuccess();

        String proxyFqn = "com.example.DispatchShapes_ServiceClientProxy";
        result.assertGeneratedSourceContains(proxyFqn, "inheritedOp(");
        result.assertGeneratedSourceContains(proxyFqn, "withSecurityContext(");
        result.assertGeneratedSourceContains(proxyFqn, "notifyDispatch(");
        result.assertGeneratedSourceContains(proxyFqn, "defaultOp(");
        result.assertGeneratedSourceContains(proxyFqn, "sendOneWay");
        result.assertGeneratedSourceContains(proxyFqn, "ContextValues.current");
    }

    @Test
    @DisplayName("nested contract interface flattens to Outer_Inner_ServiceClientProxy")
    void flattensNestedContractName() {
        JavaFileObject contract = SourceFiles.inline("com.example.Outer", """
                package com.example;
                import dev.vertique.services.ServiceContract;
                import dev.vertique.services.ServiceOperation;
                import io.vertx.core.Future;
                public class Outer {
                    @ServiceContract("nested-contract")
                    public interface Inner {
                        @ServiceOperation("op")
                        Future<String> op(String value);
                    }
                }
                """);

        JavaFileObject[] sources = concat(FRAMEWORK_SOURCES, contract);
        var result = ProcessorTestHarness.run(new ServiceContractProcessor(), sources);

        result.assertSuccess();
        result.assertGeneratedSourceContains(
                "com.example.Outer_Inner_ServiceClientProxy", "class Outer_Inner_ServiceClientProxy");
    }

    @Test
    @DisplayName("concrete inheritor of a generic super-interface emits with the substituted signature")
    void emitsForConcreteInheritorOfGenericSuperInterface() {
        JavaFileObject parent = SourceFiles.inline("com.example.Parent", """
                package com.example;
                import dev.vertique.services.ServiceOperation;
                import io.vertx.core.Future;
                public interface Parent<T> {
                    @ServiceOperation("get")
                    Future<T> get(T in);
                }
                """);

        JavaFileObject child = SourceFiles.inline("com.example.Child", """
                package com.example;
                import dev.vertique.services.ServiceContract;
                @ServiceContract("child")
                public interface Child extends Parent<String> {
                }
                """);

        JavaFileObject[] sources = concat(FRAMEWORK_SOURCES, parent, child);
        var result = ProcessorTestHarness.run(new ServiceContractProcessor(), sources);

        result.assertSuccess();

        String proxyFqn = "com.example.Child_ServiceClientProxy";
        result.assertGeneratedSourceContains(proxyFqn, "implements Child");
        result.assertGeneratedSourceContains(proxyFqn, "Future<String>");
        result.assertGeneratedSourceDoesNotContain(proxyFqn, "Future<T>");
    }

    @Test
    @DisplayName("generic contract skips emission and emits a NOTE diagnostic naming the contract")
    void skipsGenericContractWithNote() {
        JavaFileObject contract = SourceFiles.inline("com.example.GenericContract", """
                package com.example;
                import dev.vertique.services.ServiceContract;
                import dev.vertique.services.ServiceOperation;
                import io.vertx.core.Future;
                @ServiceContract("generic-contract")
                public interface GenericContract<T> {
                    @ServiceOperation("op")
                    Future<T> op(T value);
                }
                """);

        JavaFileObject[] sources = concat(FRAMEWORK_SOURCES, contract);
        var result = ProcessorTestHarness.run(new ServiceContractProcessor(), sources);

        result.assertSuccess();
        assertNoGeneratedSource(result, "com.example.GenericContract_ServiceClientProxy");
        assertNoteContaining(result, "GenericContract");
    }

    @Test
    @DisplayName("generic method on a non-generic contract skips emission and emits a NOTE diagnostic")
    void skipsGenericMethodContractWithNote() {
        JavaFileObject contract = SourceFiles.inline("com.example.GenericMethodContract", """
                package com.example;
                import dev.vertique.services.ServiceContract;
                import dev.vertique.services.ServiceOperation;
                import io.vertx.core.Future;
                @ServiceContract("generic-method-contract")
                public interface GenericMethodContract {
                    @ServiceOperation("op")
                    <T> Future<T> op(T value);
                }
                """);

        JavaFileObject[] sources = concat(FRAMEWORK_SOURCES, contract);
        var result = ProcessorTestHarness.run(new ServiceContractProcessor(), sources);

        result.assertSuccess();
        assertNoGeneratedSource(result, "com.example.GenericMethodContract_ServiceClientProxy");
        assertNoteContaining(result, "GenericMethodContract");
    }

    @Test
    @DisplayName("static helper method on the contract is excluded from the emitted proxy")
    void skipsStaticHelperMethod() {
        JavaFileObject contract = SourceFiles.inline("com.example.StaticHelperContract", """
                package com.example;
                import dev.vertique.services.ServiceContract;
                import dev.vertique.services.ServiceOperation;
                import io.vertx.core.Future;
                @ServiceContract("static-helper-contract")
                public interface StaticHelperContract {
                    @ServiceOperation("op")
                    Future<String> op(String value);

                    static String helperConstant() {
                        return "constant";
                    }
                }
                """);

        JavaFileObject[] sources = concat(FRAMEWORK_SOURCES, contract);
        var result = ProcessorTestHarness.run(new ServiceContractProcessor(), sources);

        result.assertSuccess();

        String proxyFqn = "com.example.StaticHelperContract_ServiceClientProxy";
        result.assertGeneratedSourceContains(proxyFqn, "class StaticHelperContract_ServiceClientProxy");
        result.assertGeneratedSourceDoesNotContain(proxyFqn, "helperConstant");
    }

    @Test
    @DisplayName("plain interface without @ServiceContract is never given a proxy")
    void ignoresUnannotatedInterface() {
        JavaFileObject marker = SourceFiles.inline("com.example.MarkerContract", """
                package com.example;
                import dev.vertique.services.ServiceContract;
                import dev.vertique.services.ServiceOperation;
                import io.vertx.core.Future;
                @ServiceContract("marker-contract")
                public interface MarkerContract {
                    @ServiceOperation("op")
                    Future<String> op(String value);
                }
                """);

        JavaFileObject plain = SourceFiles.inline("com.example.PlainInterface", """
                package com.example;
                import io.vertx.core.Future;
                public interface PlainInterface {
                    Future<String> plainOp(String value);
                }
                """);

        JavaFileObject[] sources = concat(FRAMEWORK_SOURCES, marker, plain);
        var result = ProcessorTestHarness.run(new ServiceContractProcessor(), sources);

        result.assertSuccess();
        result.assertGeneratedSourceContains(
                "com.example.MarkerContract_ServiceClientProxy", "implements MarkerContract");
        assertNoGeneratedSource(result, "com.example.PlainInterface_ServiceClientProxy");
    }

    @Test
    @DisplayName("generated constructor bakes the pinned mismatch-message prefix")
    void bakesMismatchMessagePrefix() {
        JavaFileObject contract = SourceFiles.inline("com.example.Greeter", """
                package com.example;
                import dev.vertique.services.ServiceContract;
                import dev.vertique.services.ServiceOperation;
                import io.vertx.core.Future;
                @ServiceContract("greeter")
                public interface Greeter {
                    @ServiceOperation("greet")
                    Future<String> greet(String name);
                }
                """);

        JavaFileObject[] sources = concat(FRAMEWORK_SOURCES, contract);
        var result = ProcessorTestHarness.run(new ServiceContractProcessor(), sources);

        result.assertSuccess();
        result.assertGeneratedSourceContains(
                "com.example.Greeter_ServiceClientProxy", "Service client contract mismatch: ");
    }

    // --- Helpers ---

    /**
     * Asserts that no generated source file exists for the given fully-qualified class name.
     *
     * <p>Unlike {@link Result#assertGeneratedSourceDoesNotContain}, this does not require the file
     * to exist first — it is the correct negative assertion for "this contract must not produce a
     * proxy at all" (as opposed to "the proxy exists but must not mention X").
     *
     * @param result the harness result to inspect; must not be {@code null}
     * @param fqn    the fully-qualified class name expected to be absent from generated sources
     */
    private static void assertNoGeneratedSource(Result result, String fqn) {
        assertTrue(
                result.compilation().generatedSourceFile(fqn).isEmpty(),
                "Expected no generated source for '%s' but one was found".formatted(fqn));
    }

    /**
     * Asserts that at least one {@link Diagnostic.Kind#NOTE} diagnostic contains the given
     * substring.
     *
     * @param result    the harness result to inspect; must not be {@code null}
     * @param substring the expected substring in at least one NOTE diagnostic message; must not be
     *                  {@code null}
     */
    private static void assertNoteContaining(Result result, String substring) {
        boolean found = result.compilation().diagnostics().stream()
                .anyMatch(d -> d.getKind() == Diagnostic.Kind.NOTE
                        && d.getMessage(null) != null
                        && d.getMessage(null).contains(substring));
        assertTrue(found, "Expected a NOTE diagnostic containing '%s' but none was found".formatted(substring));
    }

    private static JavaFileObject[] concat(JavaFileObject[] base, JavaFileObject... extra) {
        var result = new JavaFileObject[base.length + extra.length];
        System.arraycopy(base, 0, result, 0, base.length);
        System.arraycopy(extra, 0, result, base.length, extra.length);
        return result;
    }
}
