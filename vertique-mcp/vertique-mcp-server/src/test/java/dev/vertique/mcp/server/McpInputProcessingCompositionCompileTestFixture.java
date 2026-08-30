// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import dagger.internal.codegen.ComponentProcessor;
import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import java.util.ArrayList;
import java.util.List;
import javax.tools.JavaFileObject;

/**
 * Framework wiring for {@link McpInputProcessingCompositionCompileTest} (T014 TP-001).
 *
 * <p>Compiles one synthetic {@code dev.vertique.mcp.server} component that includes the real
 * production {@link McpServerModule} with the real Dagger {@link ComponentProcessor} — the same
 * processor that runs over every application's own component. Two variants are compiled, each in
 * its own isolated in-memory compilation: the complete composition additionally installs a module
 * providing {@code InputObjectProcessor} (standing in for the application-supplied module, e.g.
 * {@code SanitizationModule}); the omitting composition is otherwise byte-for-byte identical minus
 * that one module.
 *
 * <p>Both variants request only {@code Set<ComposeValidator>} — the same entry point the framework's
 * own {@code ComposeValidationStep} requests from every real application's root component — so
 * neither fixture needs to stand up the mount's unrelated bindings ({@code SecurityRuntime},
 * {@code IdentityResolutionMiddleware}, ...): only {@link McpServerConfig} and
 * {@code JsonMapperProfileRegistry}, which {@code McpJsonProfileDefaultValidator} (the module's other
 * compose validator) also requires whenever the set is materialized.
 */
final class McpInputProcessingCompositionCompileTestFixture {

    private static final String PACKAGE = "dev.vertique.mcp.server";

    private McpInputProcessingCompositionCompileTestFixture() {}

    /**
     * Compiles the complete composition: {@link McpServerModule} plus a module providing
     * {@code InputObjectProcessor}.
     *
     * @return the compile-testing result
     */
    static ProcessorTestHarness.Result compileCompleteComposition() {
        return compile(true);
    }

    /**
     * Compiles the otherwise-identical composition that omits the module providing
     * {@code InputObjectProcessor} — the sensitivity mutation reverses this by passing {@code true}.
     *
     * @return the compile-testing result
     */
    static ProcessorTestHarness.Result compileCompositionOmittingInputProcessing() {
        return compile(false);
    }

    private static ProcessorTestHarness.Result compile(boolean includeInputProcessingModule) {
        List<JavaFileObject> sources = new ArrayList<>();
        sources.add(SourceFiles.inline(PACKAGE + ".TestExternalsModule", TEST_EXTERNALS_MODULE));
        if (includeInputProcessingModule) {
            sources.add(SourceFiles.inline(PACKAGE + ".TestInputProcessingModule", TEST_INPUT_PROCESSING_MODULE));
        }
        sources.add(SourceFiles.inline(PACKAGE + ".TestComposeComponent", component(includeInputProcessingModule)));
        return ProcessorTestHarness.run(new ComponentProcessor(), sources.toArray(JavaFileObject[]::new));
    }

    private static String component(boolean includeInputProcessingModule) {
        String modules = includeInputProcessingModule
                ? "McpServerModule.class, TestExternalsModule.class, TestInputProcessingModule.class"
                : "McpServerModule.class, TestExternalsModule.class";
        return """
                package dev.vertique.mcp.server;

                import dagger.Component;
                import dev.vertique.core.lifecycle.ComposeValidator;
                import jakarta.inject.Singleton;
                import java.util.Set;

                @Singleton
                @Component(modules = {%s})
                interface TestComposeComponent {
                    Set<ComposeValidator> composeValidators();
                }
                """.formatted(modules);
    }

    /**
     * Supplies the two bindings {@code McpJsonProfileDefaultValidator} needs whenever
     * {@code Set<ComposeValidator>} is materialized: {@link McpServerConfig} (a real, disabled,
     * default instance — {@code jsonProfile()} is {@code null}, so
     * {@code JsonMapperProfileRegistry#validateConfigured} is a no-op) and a
     * {@code JsonMapperProfileRegistry} double whose methods are never actually invoked by this
     * fixture and therefore only need to exist, not behave.
     */
    private static final String TEST_EXTERNALS_MODULE = """
            package dev.vertique.mcp.server;

            import com.fasterxml.jackson.databind.ObjectMapper;
            import dagger.Module;
            import dagger.Provides;
            import dev.vertique.core.json.JsonMapperProfile;
            import dev.vertique.core.json.JsonMapperProfileRegistry;
            import dev.vertique.core.json.JsonProfileId;
            import jakarta.inject.Singleton;
            import java.util.Set;

            @Module
            final class TestExternalsModule {

                @Provides
                @Singleton
                static McpServerConfig config() {
                    return McpServerConfig.defaults();
                }

                @Provides
                @Singleton
                static JsonMapperProfileRegistry registry() {
                    return new JsonMapperProfileRegistry() {
                        @Override
                        public ObjectMapper mapper(JsonProfileId id) {
                            throw new UnsupportedOperationException("not needed by this compile fixture");
                        }

                        @Override
                        public JsonMapperProfile profile(JsonProfileId id) {
                            throw new UnsupportedOperationException("not needed by this compile fixture");
                        }

                        @Override
                        public Set<JsonProfileId> profileIds() {
                            throw new UnsupportedOperationException("not needed by this compile fixture");
                        }
                    };
                }
            }
            """;

    /** Stands in for the application-supplied module (e.g. {@code SanitizationModule}). */
    private static final String TEST_INPUT_PROCESSING_MODULE = """
            package dev.vertique.mcp.server;

            import dagger.Module;
            import dagger.Provides;
            import dev.vertique.core.sanitization.InputFieldNameResolver;
            import dev.vertique.core.sanitization.InputLocation;
            import dev.vertique.input.processing.EffectiveInputPolicies;
            import dev.vertique.input.processing.InputObjectProcessor;
            import jakarta.inject.Singleton;
            import java.lang.reflect.Type;

            @Module
            final class TestInputProcessingModule {

                @Provides
                @Singleton
                static InputObjectProcessor inputObjectProcessor() {
                    return new InputObjectProcessor() {
                        @Override
                        public void precomputeFieldNameResolution(Type declaredType, InputFieldNameResolver resolver) {
                            // No-op: this fixture only proves the binding is required, not what it does.
                        }

                        @Override
                        public Object processInput(
                                Object input,
                                Type targetType,
                                EffectiveInputPolicies policies,
                                InputLocation location,
                                InputFieldNameResolver nameResolver) {
                            return input;
                        }
                    };
                }
            }
            """;
}
