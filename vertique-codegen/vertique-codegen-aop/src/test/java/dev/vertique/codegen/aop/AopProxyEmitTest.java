// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.aop;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link AopProcessor} emits a reflection-free {@code {Bean}$AopProxy extends Bean} subclass
 * proxy for a bean carrying an {@link dev.vertique.aop.Aspect}-meta-annotated method.
 *
 * <p>These tests pin the FROZEN generated-proxy shape (PRD Appendix A.5): the proxy extends the bean,
 * overrides the intercepted method, calls {@code super.<method>(...)} directly, holds a {@code static}
 * {@code MethodMetadata} constant, builds the interceptor chain in its constructor, and contains no
 * reflection ({@code Method.invoke} / {@code getDeclaredMethod} / {@code .getAnnotation(}).
 *
 * <p><strong>Red:</strong> the slice-1.2 scaffold {@link AopProcessor} is a no-op, so no proxy source
 * is generated and every assertion below fails.
 */
class AopProxyEmitTest {

    private static final String GREETER_FQN = "com.example.Greeter";
    private static final String PROXY_FQN = "com.example.Greeter$AopProxy";

    /** A single-{@code @Inject}-ctor bean with a {@code Future<String>} method carrying {@code @TestTimed}. */
    private static JavaFileObject greeterBean() {
        return SourceFiles.inline(GREETER_FQN, """
                package com.example;
                import dev.vertique.codegen.aop.TestTimed;
                import io.vertx.core.Future;
                import jakarta.inject.Inject;
                import jakarta.inject.Named;
                public class Greeter {
                    private final String greeting;
                    @Inject
                    public Greeter(@Named("greeting") String greeting) {
                        this.greeting = greeting;
                    }
                    @TestTimed
                    public Future<String> greet(String name) {
                        return Future.succeededFuture(greeting + " " + name);
                    }
                }
                """);
    }

    /**
     * A bean exercising the emitter's type resolution (the slice-0.2 watch-item): a method with a
     * primitive param, an array param, and a primitive return, plus a {@code void}-returning method.
     */
    private static JavaFileObject typeResolutionBean() {
        return SourceFiles.inline("com.example.Calculator", """
                package com.example;
                import dev.vertique.codegen.aop.TestTimed;
                import jakarta.inject.Inject;
                public class Calculator {
                    @Inject
                    public Calculator() {}
                    @TestTimed
                    public int sum(int seed, int[] values) {
                        int total = seed;
                        for (int v : values) {
                            total += v;
                        }
                        return total;
                    }
                    @TestTimed
                    public void reset(boolean hard) {
                        // no-op
                    }
                }
                """);
    }

    @Test
    @DisplayName(
            "a @TestTimed Future-returning method yields a Greeter$AopProxy extending Greeter that calls super.greet")
    void generatedProxyExtendsBean() {
        ProcessorTestHarness.run(new AopProcessor(), greeterBean())
                .assertSuccess()
                .assertGeneratedSourceContains(PROXY_FQN, "extends Greeter")
                .assertGeneratedSourceContains(PROXY_FQN, "@Override")
                .assertGeneratedSourceContains(PROXY_FQN, "super.greet(")
                .assertGeneratedSourceContains(PROXY_FQN, "static")
                .assertGeneratedSourceContains(PROXY_FQN, "MethodMetadata");
    }

    @Test
    @DisplayName("the generated proxy is reflection-free — no Method.invoke or getDeclaredMethod or .getAnnotation(")
    void generatedProxyHasNoReflection() {
        ProcessorTestHarness.run(new AopProcessor(), greeterBean())
                .assertSuccess()
                .assertGeneratedSourceDoesNotContain(PROXY_FQN, "getDeclaredMethod")
                .assertGeneratedSourceDoesNotContain(PROXY_FQN, "Method.invoke")
                .assertGeneratedSourceDoesNotContain(PROXY_FQN, ".getAnnotation(");
    }

    @Test
    @DisplayName("a method with a primitive param, array param, and primitive/void return resolves and compiles")
    void primitiveAndArrayTypesResolveAndCompile() {
        ProcessorTestHarness.run(new AopProcessor(), typeResolutionBean())
                .assertSuccess()
                .assertGeneratedSourceContains("com.example.Calculator$AopProxy", "extends Calculator")
                .assertGeneratedSourceContains("com.example.Calculator$AopProxy", "super.sum(")
                .assertGeneratedSourceContains("com.example.Calculator$AopProxy", "super.reset(")
                // primitive + array param types resolve to their erased forms in the metadata constant
                .assertGeneratedSourceContains("com.example.Calculator$AopProxy", "int.class")
                .assertGeneratedSourceContains("com.example.Calculator$AopProxy", "int[].class");
    }
}
