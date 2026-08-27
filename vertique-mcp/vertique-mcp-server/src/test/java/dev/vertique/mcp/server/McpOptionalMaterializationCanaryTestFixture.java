// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import dev.vertique.codegen.mcp.McpToolProcessor;
import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.input.processing.InputObjectProcessor;
import dev.vertique.json.VertxJsonSupport;
import dev.vertique.mcp.server.runtime.McpToolRuntimeFactory;
import dev.vertique.mcp.server.runtime.McpToolRuntimeFactoryTestSupport;
import dev.vertique.mcp.tool.McpToolInvoker;
import java.lang.reflect.Constructor;
import java.util.Optional;
import java.util.Set;
import javax.tools.JavaFileObject;

/**
 * Framework wiring for R03 TP-002 (finding #428): compiles one real, single-parameter
 * {@code com.example.optionalprobe.ProbeTools} application source — an {@code Optional<String>} tool
 * parameter, so {@link dev.vertique.codegen.mcp.McpToolInvokerEmitter} emits the generated same-package
 * {@code OptionalProbe} canary and the constructor call to {@code McpToolRuntime
 * #verifyOptionalMaterialization} — with the real {@link McpToolProcessor}, and constructs the
 * resulting generated {@code ProbeTools_register_McpToolInvoker} through its real four-argument
 * {@code @Inject} constructor via reflection, against a caller-chosen {@link McpToolRuntimeFactory}.
 *
 * <p>Construction is the proof point: the canary runs inside the generated invoker's constructor,
 * before this fixture (or anything else) can obtain an {@link McpToolInvoker} at all — so "the tool
 * never mounts" is exactly "construction throws" here.
 */
final class McpOptionalMaterializationCanaryTestFixture {

    static final String TOOL_PACKAGE = "com.example.optionalprobe";
    static final String TOOLS_SOURCE_FQN = TOOL_PACKAGE + ".ProbeTools";
    static final String INVOKER_FQN = TOOL_PACKAGE + ".ProbeTools_register_McpToolInvoker";

    private McpOptionalMaterializationCanaryTestFixture() {}

    /**
     * Compiles the real {@code ProbeTools} source with the real {@link McpToolProcessor} once; reused
     * across construction attempts against different {@link McpToolRuntimeFactory} instances so a
     * failing construction (the very case this proof needs) does not require recompiling.
     *
     * @return the compilation result, already asserted successful
     * @throws Exception if compilation fails to even produce a result
     */
    static ProcessorTestHarness.Result compile() throws Exception {
        JavaFileObject toolSource = SourceFiles.inline(TOOLS_SOURCE_FQN, """
                package com.example.optionalprobe;

                import dev.vertique.mcp.annotation.McpTool;
                import dev.vertique.mcp.annotation.McpToolParam;
                import jakarta.inject.Inject;
                import java.util.Optional;

                public class ProbeTools {

                    @Inject
                    public ProbeTools() {}

                    @McpTool(name = "probe.register", description = "Registers an optional nickname.")
                    public String register(
                            @McpToolParam(name = "nickname", description = "The optional nickname.")
                            Optional<String> nickname) {
                        return "ok";
                    }
                }
                """);
        ProcessorTestHarness.Result result = ProcessorTestHarness.run(new McpToolProcessor(), toolSource);
        result.assertSuccess();
        return result;
    }

    /**
     * Attempts to construct the real generated invoker against {@code runtimes}, exactly as Dagger
     * composition would.
     *
     * @param result  the already-compiled result from {@link #compile()}
     * @param runtimes the runtime factory bound to the profile under test
     * @return the constructed invoker, if construction succeeds
     * @throws Exception propagated verbatim — including a {@link
     *     dev.vertique.core.exception.ConfigurationException} thrown by the canary — never wrapped, so
     *     the test can assert on its exact type and message
     */
    static McpToolInvoker construct(ProcessorTestHarness.Result result, McpToolRuntimeFactory runtimes)
            throws Exception {
        Class<?> toolsClass = result.loadGeneratedClass(TOOLS_SOURCE_FQN);
        Class<?> invokerClass = result.loadGeneratedClass(INVOKER_FQN);
        Object toolsInstance = toolsClass.getDeclaredConstructor().newInstance();

        InputObjectProcessor processor = InputObjectProcessor.createDefault(
                canonicalizerType -> {
                    throw new IllegalArgumentException("unresolvable canonicalizer " + canonicalizerType);
                },
                sanitizerType -> {
                    throw new IllegalArgumentException("unresolvable sanitizer " + sanitizerType);
                });

        Constructor<?> invokerConstructor = invokerClass.getDeclaredConstructor(
                toolsClass, McpToolRuntimeFactory.class, InputObjectProcessor.class, Optional.class);
        invokerConstructor.setAccessible(true);
        try {
            return (McpToolInvoker)
                    invokerConstructor.newInstance(toolsInstance, runtimes, processor, Optional.empty());
        } catch (java.lang.reflect.InvocationTargetException wrapped) {
            // The generated invoker's own constructor is the direct caller of
            // McpToolRuntime#verifyOptionalMaterialization; reflection wraps whatever it throws.
            // Unwrap so the test observes the exact real exception, not java.lang.reflect's wrapper.
            if (wrapped.getCause() instanceof RuntimeException runtimeCause) {
                throw runtimeCause;
            }
            throw wrapped;
        }
    }

    /**
     * The real, zero-config default a composed application gets when it configures no JSON profile at
     * all: {@link McpToolRuntimeFactoryTestSupport#factory()}, resolved through {@code
     * McpJsonProfileResolver}'s real tail. R13 item 2 (issue #440) changed that tail from the reserved
     * {@code vertx} profile (Vert.x's {@code DatabindCodec}, which registers no {@code Jdk8Module} and
     * so cannot materialize {@code Optional<T>} correctly) to the built-in {@code vertique} profile,
     * which does — so this factory is now materialization-capable, unlike before the fix.
     *
     * @return a factory bound to the zero-config default profile
     */
    static McpToolRuntimeFactory zeroConfigProfileFactory() {
        return McpToolRuntimeFactoryTestSupport.factory();
    }

    /**
     * An explicitly selected profile whose mapper registers no {@code Jdk8Module} and therefore cannot
     * materialize {@code Optional<T>} correctly, deliberately reached through an MCP-boundary default
     * rather than the zero-config tail — proving the canary still fails startup for a genuinely
     * incapable profile regardless of which profile the zero-config tail itself resolves to (issue
     * #440 changed only the tail, not the canary's own contract).
     *
     * @return a factory bound to an explicitly selected, {@code Optional}-incapable profile
     */
    static McpToolRuntimeFactory explicitlyIncapableProfileFactory() {
        return McpToolRuntimeFactoryTestSupport.factory(Set.of(new NonMaterializingProfile()), "non-materializing");
    }

    /**
     * A registered {@code strict} application profile whose mapper actually materializes
     * {@code Optional} correctly ({@code Jdk8Module}), plus Vert.x's Jackson support so the registry's
     * structural round-trip probe passes.
     *
     * @return a factory bound to a materialization-capable profile
     */
    static McpToolRuntimeFactory materializableProfileFactory() {
        return McpToolRuntimeFactoryTestSupport.factory(Set.of(new MaterializingProfile()), "strict");
    }

    private static final class MaterializingProfile implements JsonMapperProfile {

        private final ObjectMapper mapper =
                new ObjectMapper().registerModule(new Jdk8Module()).registerModule(VertxJsonSupport.module());

        @Override
        public JsonProfileId id() {
            return JsonProfileId.of("strict");
        }

        @Override
        public ObjectMapper mapper() {
            return mapper;
        }
    }

    /** Registers Vert.x's Jackson support (so the registry's structural probe passes) but no {@code Jdk8Module}. */
    private static final class NonMaterializingProfile implements JsonMapperProfile {

        private final ObjectMapper mapper = new ObjectMapper().registerModule(VertxJsonSupport.module());

        @Override
        public JsonProfileId id() {
            return JsonProfileId.of("non-materializing");
        }

        @Override
        public ObjectMapper mapper() {
            return mapper;
        }
    }
}
