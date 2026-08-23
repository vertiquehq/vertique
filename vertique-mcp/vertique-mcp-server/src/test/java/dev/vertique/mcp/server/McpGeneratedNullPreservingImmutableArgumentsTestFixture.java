// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import dev.vertique.codegen.mcp.McpToolProcessor;
import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import dev.vertique.input.processing.InputObjectProcessor;
import dev.vertique.mcp.server.runtime.McpToolRuntimeFactory;
import dev.vertique.mcp.server.runtime.McpToolRuntimeFactoryTestSupport;
import dev.vertique.mcp.tool.McpCancellationSignal;
import dev.vertique.mcp.tool.McpToolInvoker;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import java.lang.reflect.Constructor;
import javax.tools.JavaFileObject;

/**
 * Framework wiring for {@link McpGeneratedNullPreservingImmutableArgumentsTest} (review-finding #4,
 * round-14 remediation): compiles one real, parameterized {@code com.example.nullable.NullableTools}
 * application source — a required record-typed parameter and an {@code Optional<T>} parameter,
 * neither carrying any {@code @Canonicalize}/{@code @Sanitize} annotation — with the real {@link
 * McpToolProcessor}, and loads the resulting generated {@code NullableTools_register_McpToolInvoker}
 * through its real three-argument {@code @Inject} constructor, exactly like {@link
 * McpGeneratedParameterizedToolITFixture} but without the HTTP layer: this proof only needs {@link
 * McpToolInvoker#prepare}, never the dispatcher or a live request.
 */
final class McpGeneratedNullPreservingImmutableArgumentsTestFixture {

    static final String TOOL_PACKAGE = "com.example.nullable";
    static final String TOOLS_SOURCE_FQN = TOOL_PACKAGE + ".NullableTools";
    static final String ADDRESS_SOURCE_FQN = TOOL_PACKAGE + ".Address";
    static final String INVOKER_FQN = TOOL_PACKAGE + ".NullableTools_register_McpToolInvoker";

    private final ProcessorTestHarness.Result result;
    private final McpToolInvoker invoker;

    private McpGeneratedNullPreservingImmutableArgumentsTestFixture() throws Exception {
        JavaFileObject addressSource = SourceFiles.inline(ADDRESS_SOURCE_FQN, """
                package com.example.nullable;

                public record Address(String city, String zip) {}
                """);
        JavaFileObject toolSource = SourceFiles.inline(TOOLS_SOURCE_FQN, """
                package com.example.nullable;

                import dev.vertique.mcp.annotation.McpTool;
                import dev.vertique.mcp.annotation.McpToolParam;
                import io.vertx.core.Future;
                import jakarta.inject.Inject;
                import java.util.Optional;

                public class NullableTools {

                    @Inject
                    public NullableTools() {}

                    @McpTool(name = "nullable.register", description = "Registers an address and an optional nickname.")
                    public Future<String> register(
                            @McpToolParam(name = "address", description = "The address to register.") Address address,
                            @McpToolParam(name = "nickname", description = "The optional nickname.")
                            Optional<String> nickname) {
                        return Future.succeededFuture("ok");
                    }
                }
                """);
        this.result = ProcessorTestHarness.run(new McpToolProcessor(), addressSource, toolSource);
        result.assertSuccess();
        this.invoker = loadInvoker();
    }

    static McpGeneratedNullPreservingImmutableArgumentsTestFixture start() throws Exception {
        return new McpGeneratedNullPreservingImmutableArgumentsTestFixture();
    }

    /** The loaded real generated invoker — the only entry point this proof needs. */
    McpToolInvoker invoker() {
        return invoker;
    }

    private McpToolInvoker loadInvoker() throws Exception {
        Class<?> toolsClass = result.loadGeneratedClass(TOOLS_SOURCE_FQN);
        Class<?> invokerClass = result.loadGeneratedClass(INVOKER_FQN);
        Object toolsInstance = toolsClass.getDeclaredConstructor().newInstance();

        // Neither declared parameter carries a @Canonicalize/@Sanitize annotation, so INP-001 never
        // resolves either chain for this tool — both resolvers throw if that assumption regresses.
        InputObjectProcessor processor = InputObjectProcessor.createDefault(
                canonicalizerType -> {
                    throw new IllegalArgumentException("unresolvable canonicalizer " + canonicalizerType);
                },
                sanitizerType -> {
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
