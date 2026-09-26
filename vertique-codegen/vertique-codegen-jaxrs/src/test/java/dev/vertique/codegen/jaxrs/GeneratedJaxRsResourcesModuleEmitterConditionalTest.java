// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * APT compilation tests for the presence-gated legacy binding, its paired lazy catalog entry, and
 * conditional and uniform {@code @ElementsIntoSet} binding emission in
 * {@link GeneratedJaxRsResourcesModuleEmitter} (CG-011 W3).
 *
 * <p>Each test compiles a small fixture through {@link JaxRsPipelineProcessor} and verifies
 * the shape of the generated {@code GeneratedJaxRsResourcesModule}:
 * <ul>
 *   <li>All legacy bindings use {@code @ElementsIntoSet Set<Object>} — the uniform shape
 *       (FR-CG011-020) — and also take {@code Set<GeneratedJaxRsApplicationRegistration>
 *       applications}, gating their body on {@code applications.isEmpty()} (presence-gated
 *       binding).</li>
 *   <li>All methods accept {@code @VertxConfig JsonObject config} and
 *       {@code Provider<Resource> provider} parameters (FR-CG011-021).</li>
 *   <li>Unconditional resources produce {@code applications.isEmpty() ? Set.of(provider.get()) :
 *       Set.of()} with no {@code PropertyCondition} guard.</li>
 *   <li>Conditional resources emit a {@code private static final PropertyCondition[]
 *       X_BINDING_CONDITIONS} constant and binding body {@code applications.isEmpty() &&
 *       PropertyCondition.matchesAll(config, X_BINDING_CONDITIONS) ? Set.of(provider.get()) :
 *       Set.of()}.</li>
 *   <li>Multiple {@code @ConditionalOnProperty} annotations (repeatable) are ANDed into one array.</li>
 *   <li>Every DI-eligible resource also gets a lazy {@code @Provides @IntoSet
 *       GeneratedJaxRsResourceEntry …Entry} method that never calls the provider and, when
 *       conditional, reuses the binding's own {@code X_BINDING_CONDITIONS} constant — so one
 *       conditional resource contains {@code PropertyCondition.matchesAll} twice: once gating its
 *       binding, once feeding its entry's condition result.</li>
 * </ul>
 */
class GeneratedJaxRsResourcesModuleEmitterConditionalTest {

    private static final String GENERATED_MODULE = "dev.vertique.test.GeneratedJaxRsResourcesModule";

    // --- Unconditional resource — uniform shape ---

    @Nested
    @DisplayName("Unconditional resource")
    class Unconditional {

        @Test
        @DisplayName("plain resource emits @ElementsIntoSet with Set.of(provider.get()) body")
        void unconditionalResource_emitsElementsIntoSetWithLiteralProviderGet() {
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.UserResource", """
                            package dev.vertique.test;

                            import jakarta.inject.Inject;
                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;

                            @Path("/users")
                            public class UserResource {
                                @Inject
                                public UserResource() {}

                                @GET
                                public String list() { return ""; }
                            }
                            """));

            result.assertSuccess();

            // Uniform shape: @ElementsIntoSet not @IntoSet
            result.assertGeneratedSourceContains(GENERATED_MODULE, "@ElementsIntoSet");

            // Return type must be Set<Object>
            result.assertGeneratedSourceContains(GENERATED_MODULE, "Set<Object>");

            // @VertxConfig parameter must be present
            result.assertGeneratedSourceContains(GENERATED_MODULE, "@VertxConfig");

            // Provider<UserResource> parameter must be present
            result.assertGeneratedSourceContains(GENERATED_MODULE, "Provider<UserResource>");

            // Unconditional body: simply return Set.of(provider.get())
            result.assertGeneratedSourceContains(GENERATED_MODULE, "Set.of(provider.get())");

            // No conditional guard in unconditional path
            boolean hasConditionGuard = result.compilation()
                    .generatedSourceFile(GENERATED_MODULE)
                    .map(f -> {
                        try {
                            return f.getCharContent(true).toString();
                        } catch (java.io.IOException e) {
                            return "";
                        }
                    })
                    .map(s -> s.contains("PropertyCondition.matchesAll"))
                    .orElse(false);
            org.junit.jupiter.api.Assertions.assertFalse(
                    hasConditionGuard, "Unconditional resource must not contain PropertyCondition.matchesAll guard");
        }
    }

    // --- Conditional resource — single @ConditionalOnProperty ---

    @Nested
    @DisplayName("Conditional resource — single @ConditionalOnProperty")
    class ConditionalSingle {

        @Test
        @DisplayName("single @ConditionalOnProperty emits PropertyCondition[] constant and guarded body")
        void conditionalResource_emitsConditionsConstantAndGuardedBody() {
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.AdminResource", """
                            package dev.vertique.test;

                            import dev.vertique.codegen.ConditionalOnProperty;
                            import jakarta.inject.Inject;
                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;

                            @Path("/admin")
                            @ConditionalOnProperty(name = "adminApi.enabled")
                            public class AdminResource {
                                @Inject
                                public AdminResource() {}

                                @GET
                                public String get() { return ""; }
                            }
                            """));

            result.assertSuccess();

            // PropertyCondition[] constant with default havingValue="true" and matchIfMissing=false
            result.assertGeneratedSourceContains(
                    GENERATED_MODULE, "new PropertyCondition(\"adminApi.enabled\", \"true\", false)");

            // The constant must declare a PropertyCondition[] array
            result.assertGeneratedSourceContains(GENERATED_MODULE, "PropertyCondition[]");

            // Guarded body
            result.assertGeneratedSourceContains(GENERATED_MODULE, "PropertyCondition.matchesAll(config,");
            result.assertGeneratedSourceContains(GENERATED_MODULE, "? Set.of(provider.get()) : Set.of()");

            // Import for PropertyCondition
            result.assertGeneratedSourceContains(GENERATED_MODULE, "dev.vertique.core.config.PropertyCondition");
        }

        @Test
        @DisplayName("custom havingValue and matchIfMissing=true are propagated to generated PropertyCondition literal")
        void conditionalResource_customHavingValueAndMatchIfMissing_propagated() {
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.SandboxResource", """
                            package dev.vertique.test;

                            import dev.vertique.codegen.ConditionalOnProperty;
                            import jakarta.inject.Inject;
                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;

                            @Path("/sandbox")
                            @ConditionalOnProperty(name = "mode", havingValue = "sandbox", matchIfMissing = true)
                            public class SandboxResource {
                                @Inject
                                public SandboxResource() {}

                                @GET
                                public String get() { return ""; }
                            }
                            """));

            result.assertSuccess();

            // Custom havingValue and matchIfMissing=true must appear verbatim in the PropertyCondition literal
            result.assertGeneratedSourceContains(
                    GENERATED_MODULE, "new PropertyCondition(\"mode\", \"sandbox\", true)");
        }
    }

    // --- Conditional resource — multiple @ConditionalOnProperty (repeatable) ---

    @Nested
    @DisplayName("Conditional resource — multiple @ConditionalOnProperty")
    class ConditionalMultiple {

        @Test
        @DisplayName("two @ConditionalOnProperty annotations are ANDed into one PropertyCondition[] array")
        void conditionalResource_multipleAnnotations_andsAllInOneArray() {
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.FeatureResource", """
                            package dev.vertique.test;

                            import dev.vertique.codegen.ConditionalOnProperty;
                            import jakarta.inject.Inject;
                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;

                            @Path("/feature")
                            @ConditionalOnProperty(name = "feature.a.enabled")
                            @ConditionalOnProperty(name = "feature.b.enabled")
                            public class FeatureResource {
                                @Inject
                                public FeatureResource() {}

                                @GET
                                public String get() { return ""; }
                            }
                            """));

            result.assertSuccess();

            // Both conditions must appear in the generated source
            result.assertGeneratedSourceContains(
                    GENERATED_MODULE, "new PropertyCondition(\"feature.a.enabled\", \"true\", false)");
            result.assertGeneratedSourceContains(
                    GENERATED_MODULE, "new PropertyCondition(\"feature.b.enabled\", \"true\", false)");

            // Guard references the same PropertyCondition[] constant
            result.assertGeneratedSourceContains(GENERATED_MODULE, "PropertyCondition.matchesAll(config,");
        }
    }

    // --- Mixed: conditional and unconditional in same module ---

    @Nested
    @DisplayName("Mixed conditional and unconditional resources")
    class Mixed {

        @Test
        @DisplayName("module with both conditional and unconditional resources emits both binding shapes")
        void mixed_conditionalAndUnconditional_inSameModule() {
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(),
                    SourceFiles.inline("dev.vertique.test.PublicResource", """
                            package dev.vertique.test;

                            import jakarta.inject.Inject;
                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;

                            @Path("/public")
                            public class PublicResource {
                                @Inject
                                public PublicResource() {}

                                @GET
                                public String get() { return ""; }
                            }
                            """),
                    SourceFiles.inline("dev.vertique.test.PrivateResource", """
                            package dev.vertique.test;

                            import dev.vertique.codegen.ConditionalOnProperty;
                            import jakarta.inject.Inject;
                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;

                            @Path("/private")
                            @ConditionalOnProperty(name = "privateApi.enabled")
                            public class PrivateResource {
                                @Inject
                                public PrivateResource() {}

                                @GET
                                public String get() { return ""; }
                            }
                            """));

            result.assertSuccess();

            // Both method names appear
            result.assertGeneratedSourceContains(GENERATED_MODULE, "publicResourceBinding");
            result.assertGeneratedSourceContains(GENERATED_MODULE, "privateResourceBinding");

            String source = result.compilation()
                    .generatedSourceFile(GENERATED_MODULE)
                    .map(f -> {
                        try {
                            return f.getCharContent(true).toString();
                        } catch (java.io.IOException e) {
                            return "";
                        }
                    })
                    .orElse("");

            // Conditional resource has a PropertyCondition.matchesAll guard
            org.junit.jupiter.api.Assertions.assertTrue(
                    source.contains("PropertyCondition.matchesAll"),
                    "Conditional resource must have a PropertyCondition.matchesAll guard");

            // The conditional resource contributes two guard sites: the presence-gated legacy
            // binding and the lazy catalog entry, both reusing the same
            // PRIVATE_RESOURCE_BINDING_CONDITIONS constant. The unconditional resource contributes
            // none.
            long guardCount = source.lines()
                    .filter(line -> line.contains("PropertyCondition.matchesAll"))
                    .count();
            org.junit.jupiter.api.Assertions.assertEquals(
                    2L,
                    guardCount,
                    "Exactly two PropertyCondition.matchesAll guards expected — one in the binding"
                            + " and one in the catalog entry, for one conditional resource");

            // One occurrence gates the presence-gated legacy binding...
            result.assertGeneratedSourceContains(
                    GENERATED_MODULE,
                    "applications.isEmpty() && PropertyCondition.matchesAll(config,"
                            + " PRIVATE_RESOURCE_BINDING_CONDITIONS)");

            // ...and the other feeds the lazy catalog entry's condition result, reusing the same
            // constant rather than declaring a second one.
            result.assertGeneratedSourceContains(
                    GENERATED_MODULE,
                    "GeneratedJaxRsResourceEntry.of(PrivateResource.class,"
                            + " PropertyCondition.matchesAll(config, PRIVATE_RESOURCE_BINDING_CONDITIONS), provider)");
        }
    }
}
