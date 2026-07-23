// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.aop;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RED reproduction for slice 2.4 (proxyability diagnostics, FR-013-09a + PRD §12 folded rows): the
 * subclass-proxy AOP strategy cannot proxy certain method/class shapes, so {@link AopProcessor} MUST
 * REJECT them with a clear {@code Diagnostics.error} <em>before</em> emission — rather than emitting
 * uncompilable {@code extends Bean} / {@code @Override} source (or, for the final-class / final-method
 * / static cases, silently compiling because the override is mishandled).
 *
 * <p>Each shape exercised here is non-proxyable:
 * <ul>
 *   <li><strong>final class</strong> — the proxy cannot {@code extends FinalGreeter} (a final class
 *       cannot be subclassed), so any generated {@code FinalGreeter$AopProxy extends FinalGreeter}
 *       is uncompilable; the processor must reject the class up front.
 *   <li><strong>final intercepted method</strong> — a {@code final} method cannot be {@code @Override}n
 *       by the proxy subclass.
 *   <li><strong>private intercepted method</strong> — a {@code private} method is not visible to a
 *       subclass and cannot be overridden.
 *   <li><strong>static intercepted method</strong> — a {@code static} method is not an instance method
 *       and cannot be overridden; aspect interception requires an instance dispatch.
 *   <li><strong>type-variable {@code throws E}</strong> — a checked throw typed as a method type
 *       variable ({@code <E extends IOException> … throws E}) makes the generated sync guard emit a
 *       non-reifiable {@code instanceof E} (R3-1), which is a confusing generated-source compile error
 *       rather than a clear AOP diagnostic.
 * </ul>
 *
 * <p><strong>Red now</strong> (no proxyability validation exists yet — slice 2.4 GREEN adds it).
 * Every shape already produces a compile <em>failure</em> today — but with a <em>confusing,
 * generated-source</em> {@code javac} error rather than the clear up-front AOP diagnostic these tests
 * assert on. So {@code assertFailed()} passes but {@code assertErrorMessage(...)} fails: the chosen
 * substring is deliberately absent from the current confusing message and only a deliberate slice-2.4
 * diagnostic will contain it. The current confusing messages (verified 2026-06-28) are:
 * <ul>
 *   <li>{@code finalClassRejected} — {@code "cannot inherit from final com.example.FinalGreeter"}
 *       ({@code FinalGreeter$AopProxy extends FinalGreeter} is emitted then fails to compile).
 *   <li>{@code finalMethodRejected} — {@code "greet() in …$AopProxy cannot override greet() in
 *       FinalMethodGreeter"}.
 *   <li>{@code privateMethodRejected} — {@code "greet() has private access in PrivateMethodGreeter"}
 *       + {@code "method does not override or implement a method from a supertype"}.
 *   <li>{@code staticMethodRejected} — {@code "greet() in …$AopProxy cannot override greet() in
 *       StaticMethodGreeter"} + {@code "method does not override or implement a method from a
 *       supertype"}.
 *   <li>{@code typeVariableThrowsRejected} — {@code "java.lang.Throwable cannot be safely cast to E"}
 *       (the non-reifiable {@code instanceof E} the sync guard emits — R3-1).
 * </ul>
 */
class ProxyabilityDiagnosticsTest {

    /** A {@code final class} carrying a {@code @TestTimed} aspect method — cannot be subclassed. */
    private static JavaFileObject finalClassBean() {
        return SourceFiles.inline("com.example.FinalGreeter", """
                package com.example;
                import dev.vertique.codegen.aop.TestTimed;
                import io.vertx.core.Future;
                import jakarta.inject.Inject;
                public final class FinalGreeter {
                    @Inject
                    public FinalGreeter() {}
                    @TestTimed
                    public Future<String> greet() {
                        return Future.succeededFuture("hi");
                    }
                }
                """);
    }

    /** A non-final bean with a {@code final} {@code @TestTimed} method — cannot be overridden. */
    private static JavaFileObject finalMethodBean() {
        return SourceFiles.inline("com.example.FinalMethodGreeter", """
                package com.example;
                import dev.vertique.codegen.aop.TestTimed;
                import io.vertx.core.Future;
                import jakarta.inject.Inject;
                public class FinalMethodGreeter {
                    @Inject
                    public FinalMethodGreeter() {}
                    @TestTimed
                    public final Future<String> greet() {
                        return Future.succeededFuture("hi");
                    }
                }
                """);
    }

    /** A bean with a {@code private} {@code @TestTimed} method — not visible to a subclass. */
    private static JavaFileObject privateMethodBean() {
        return SourceFiles.inline("com.example.PrivateMethodGreeter", """
                package com.example;
                import dev.vertique.codegen.aop.TestTimed;
                import io.vertx.core.Future;
                import jakarta.inject.Inject;
                public class PrivateMethodGreeter {
                    @Inject
                    public PrivateMethodGreeter() {}
                    @TestTimed
                    private Future<String> greet() {
                        return Future.succeededFuture("hi");
                    }
                }
                """);
    }

    /** A bean with a {@code static} {@code @TestTimed} method — not an instance method. */
    private static JavaFileObject staticMethodBean() {
        return SourceFiles.inline("com.example.StaticMethodGreeter", """
                package com.example;
                import dev.vertique.codegen.aop.TestTimed;
                import io.vertx.core.Future;
                import jakarta.inject.Inject;
                public class StaticMethodGreeter {
                    @Inject
                    public StaticMethodGreeter() {}
                    @TestTimed
                    public static Future<String> greet() {
                        return Future.succeededFuture("hi");
                    }
                }
                """);
    }

    /** A bean with a method type-variable checked {@code throws E} — non-reifiable in the sync guard. */
    private static JavaFileObject typeVariableThrowsBean() {
        return SourceFiles.inline("com.example.TypeVarThrowsGreeter", """
                package com.example;
                import dev.vertique.codegen.aop.TestTimed;
                import jakarta.inject.Inject;
                public class TypeVarThrowsGreeter {
                    @Inject
                    public TypeVarThrowsGreeter() {}
                    @TestTimed
                    public <E extends java.io.IOException> String boom() throws E {
                        return "ok";
                    }
                }
                """);
    }

    @Test
    @DisplayName("a final class carrying an aspect method is rejected with a clear non-proxyable diagnostic")
    void finalClassRejected() {
        ProcessorTestHarness.run(new AopProcessor(), finalClassBean())
                .assertFailed()
                // "is final" mentions the modifier and is absent from the current confusing
                // "cannot inherit from final com.example.FinalGreeter" javac error → genuinely RED.
                .assertErrorMessage("is final");
    }

    @Test
    @DisplayName("a final intercepted method is rejected with a clear non-overrideable diagnostic")
    void finalMethodRejected() {
        ProcessorTestHarness.run(new AopProcessor(), finalMethodBean())
                .assertFailed()
                // "final method" mentions the modifier and is absent from the current confusing
                // "cannot override … / overridden method is final" javac error → genuinely RED.
                .assertErrorMessage("final method");
    }

    @Test
    @DisplayName("a private intercepted method is rejected with a clear non-overrideable diagnostic")
    void privateMethodRejected() {
        ProcessorTestHarness.run(new AopProcessor(), privateMethodBean())
                .assertFailed()
                // "is private" mentions the modifier and is absent from the current confusing
                // "greet() has private access in …" javac error → genuinely RED. (APT does surface
                // private-method annotations, so the private shape compiles to a broken override.)
                .assertErrorMessage("is private");
    }

    @Test
    @DisplayName("a static intercepted method is rejected with a clear non-instance diagnostic")
    void staticMethodRejected() {
        ProcessorTestHarness.run(new AopProcessor(), staticMethodBean())
                .assertFailed()
                // "static method" mentions the modifier and is absent from the current confusing
                // "cannot override … / overridden method is static" javac error → genuinely RED.
                .assertErrorMessage("static method");
    }

    @Test
    @DisplayName("a type-variable throws on an intercepted method is rejected with a clear diagnostic")
    void typeVariableThrowsRejected() {
        ProcessorTestHarness.run(new AopProcessor(), typeVariableThrowsBean())
                .assertFailed()
                .assertErrorMessage("type variable");
    }
}
