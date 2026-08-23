// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import dev.vertique.codegen.mcp.McpToolProcessor;
import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import dev.vertique.input.processing.InputObjectProcessor;
import dev.vertique.input.processing.testkit.CrossTransportUppercaseSanitizer;
import dev.vertique.mcp.server.runtime.McpToolRuntimeFactory;
import dev.vertique.mcp.server.runtime.McpToolRuntimeFactoryTestSupport;
import dev.vertique.mcp.tool.McpCancellationSignal;
import dev.vertique.mcp.tool.McpToolInvoker;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import java.lang.reflect.Constructor;
import javax.tools.JavaFileObject;

/**
 * Framework wiring for {@link McpCrossTransportInputParityTest} (R03 TP-001, finding #425): compiles
 * one real {@code com.example.parity.ParityTools} application source — a single parameter typed as the
 * <strong>published</strong> {@link dev.vertique.input.processing.testkit.CrossTransportFixtureLevel1}
 * corpus record, not a locally re-declared lookalike — with the real {@link McpToolProcessor}, and
 * loads the resulting generated {@code ParityTools_echo_McpToolInvoker} through its real three-argument
 * {@code @Inject} constructor. This is the same construction path {@link
 * McpGeneratedNullPreservingImmutableArgumentsTestFixture} uses, applied to the shared corpus type
 * instead of a locally declared one, so the proof runs the real generated stage-2 INP-001 call against
 * the exact published fixture — never a second, independently authored copy of it.
 */
final class McpCrossTransportInputParityTestFixture {

    static final String TOOL_PACKAGE = "com.example.parity";
    static final String TOOLS_SOURCE_FQN = TOOL_PACKAGE + ".ParityTools";
    static final String INVOKER_FQN = TOOL_PACKAGE + ".ParityTools_echo_McpToolInvoker";

    private final ProcessorTestHarness.Result result;
    private final McpToolInvoker invoker;

    private McpCrossTransportInputParityTestFixture() throws Exception {
        JavaFileObject toolSource = SourceFiles.inline(TOOLS_SOURCE_FQN, """
                package com.example.parity;

                import dev.vertique.input.processing.testkit.CrossTransportFixtureLevel1;
                import dev.vertique.mcp.annotation.McpTool;
                import dev.vertique.mcp.annotation.McpToolParam;
                import jakarta.inject.Inject;

                public class ParityTools {

                    @Inject
                    public ParityTools() {}

                    @McpTool(name = "parity.echo", description = "Echoes the published cross-transport corpus body.")
                    public String echo(
                            @McpToolParam(name = "root", description = "The corpus root record.")
                            CrossTransportFixtureLevel1 root) {
                        return "ok";
                    }
                }
                """);
        this.result = ProcessorTestHarness.run(new McpToolProcessor(), toolSource);
        result.assertSuccess();
        this.invoker = loadInvoker();
    }

    static McpCrossTransportInputParityTestFixture start() throws Exception {
        return new McpCrossTransportInputParityTestFixture();
    }

    /** The loaded real generated invoker — the only entry point this proof needs. */
    McpToolInvoker invoker() {
        return invoker;
    }

    private McpToolInvoker loadInvoker() throws Exception {
        Class<?> toolsClass = result.loadGeneratedClass(TOOLS_SOURCE_FQN);
        Class<?> invokerClass = result.loadGeneratedClass(INVOKER_FQN);
        Object toolsInstance = toolsClass.getDeclaredConstructor().newInstance();

        // The corpus DTO's own field-level @Sanitize declares CrossTransportUppercaseSanitizer;
        // resolving any other class here would be a bug this fixture must not silently mask.
        InputObjectProcessor processor = InputObjectProcessor.createDefault(
                canonicalizerType -> {
                    throw new IllegalArgumentException("unresolvable canonicalizer " + canonicalizerType);
                },
                sanitizerType -> {
                    if (sanitizerType == CrossTransportUppercaseSanitizer.class) {
                        return new CrossTransportUppercaseSanitizer();
                    }
                    throw new IllegalArgumentException("unresolvable sanitizer " + sanitizerType);
                });

        McpToolRuntimeFactory runtimes = McpToolRuntimeFactoryTestSupport.factory();
        Constructor<?> invokerConstructor = invokerClass.getDeclaredConstructor(
                toolsClass, McpToolRuntimeFactory.class, InputObjectProcessor.class);
        invokerConstructor.setAccessible(true);
        return (McpToolInvoker) invokerConstructor.newInstance(toolsInstance, runtimes, processor);
    }

    /** A cancellation signal that never fires, standing in for the completion coordinator's real one. */
    static final class NeverCancelledSignal implements McpCancellationSignal {
        private final Future<Void> neverCompletes = Promise.<Void>promise().future();

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public Future<Void> cancelled() {
            return neverCompletes;
        }
    }
}
