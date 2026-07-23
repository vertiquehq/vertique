// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.aop;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RED reproduction for Bug N1: the generated terminal super-call ignores the live {@code args[]}
 * array.
 *
 * <p>{@code AopProxyEmitter#buildOverride} copies the intercepted method's parameters into a local
 * {@code Object[] args = {...}} array (so an aspect can mutate {@code invocation.arguments()[i]}),
 * but then builds the terminal lambda as {@code () -> super.greet(name)} — passing the
 * <em>original parameter locals</em> rather than reading back from the (possibly mutated) array.
 * Per PRD Appendix A.5 the terminal must read the live array: {@code () -> super.greet((String)
 * args[0])}. As written, an aspect that rewrites {@code invocation.arguments()[i]} is a silent
 * no-op — the original argument value reaches {@code super}.
 *
 * <p>The bean's {@code greet} method takes a single {@code String} parameter, so a correct terminal
 * reads {@code (String) args[0]}. The test asserts the generated proxy's terminal super-call reads
 * from the {@code args} array, not from the bare original parameter name.
 *
 * <p>Currently RED: the emitter passes the original {@code name} local to {@code super.greet(...)},
 * so {@code args[0]} never appears inside the terminal super-call and the assertion fails.
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
                // The terminal must read the (possibly aspect-mutated) live array — per PRD A.5:
                // super.greet((String) args[0]). RED today: emitter passes the original `name` local.
                .assertGeneratedSourceContains(PROXY_FQN, "super.greet((String) args[0]")
                // And it must NOT pass the bare original parameter name straight to super.
                .assertGeneratedSourceDoesNotContain(PROXY_FQN, "super.greet(name)");
    }
}
