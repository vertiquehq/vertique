// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.aop;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The generated terminal super-call reads the live {@code args[]} array.
 *
 * <p>{@code AopProxyEmitter#buildOverride} copies the intercepted method's parameters into a local
 * {@code Object[] args = {...}} array (so an aspect can mutate {@code invocation.arguments()[i]}),
 * and builds the terminal lambda as {@code () -> super.greet((String) args[0])}, reading back from
 * the (possibly mutated) array rather than passing the <em>original parameter locals</em>. Passing
 * the locals would make an aspect that rewrites {@code invocation.arguments()[i]} a silent no-op,
 * because the original argument value would reach {@code super}.
 *
 * <p>The bean's {@code greet} method takes a single {@code String} parameter, so the terminal reads
 * {@code (String) args[0]}. The test asserts the generated proxy's terminal super-call reads from
 * the {@code args} array, not from the bare original parameter name.
 */
class TerminalReadsLiveArgsTest {

    private static final String GREETER_FQN = "com.example.Greeter";
    private static final String PROXY_FQN = "com.example.Greeter$AopProxy";

    /** A bean with a single-{@code String}-param {@code Future}-returning {@code @TestTimed} method. */
    private static JavaFileObject greeterBean() {
        return SourceFiles.inline(GREETER_FQN, """
                package com.example;
                import dev.vertique.codegen.aop.TestTimed;
                import io.vertx.core.Future;
                import jakarta.inject.Inject;
                public class Greeter {
                    @Inject
                    public Greeter() {}
                    @TestTimed
                    public Future<String> greet(String name) {
                        return Future.succeededFuture("hello " + name);
                    }
                }
                """);
    }

    @Test
    @DisplayName("the terminal super-call reads the live args[] array, not the original parameter locals")
    void terminalSuperCallReadsLiveArgsArray() {
        ProcessorTestHarness.run(new AopProcessor(), greeterBean())
                .assertSuccess()
                // The terminal must read the (possibly aspect-mutated) live array:
                // super.greet((String) args[0]).
                .assertGeneratedSourceContains(PROXY_FQN, "super.greet((String) args[0]")
                // And it must NOT pass the bare original parameter name straight to super.
                .assertGeneratedSourceDoesNotContain(PROXY_FQN, "super.greet(name)");
    }
}
