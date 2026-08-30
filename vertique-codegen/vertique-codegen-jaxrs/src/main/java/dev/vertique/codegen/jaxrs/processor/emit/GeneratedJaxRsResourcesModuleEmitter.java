// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs.processor.emit;

import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ArrayTypeName;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.ParameterSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.Conditions;
import dev.vertique.codegen.PackageResolver;
import dev.vertique.codegen.dagger.DaggerModuleWriter;
import java.beans.Introspector;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;

/**
 * Emitter that generates a Dagger {@code @Module} containing
 * {@code @Provides @ElementsIntoSet @JaxRsResources Set<Object>} methods for each DI-eligible
 * JAX-RS resource.
 *
 * <p>Two binding shapes are produced, both contributing to the {@code @JaxRsResources Set<Object>}
 * multibinding declared by {@code RestCoreModule}:
 *
 * <ul>
 *   <li><strong>Unconditional</strong>: <pre>{@code
 *     @Provides @ElementsIntoSet @JaxRsResources
 *     static Set<Object> userResourceBinding(@VertxConfig JsonObject config, Provider<UserResource> provider) {
 *         return Set.of(provider.get());
 *     }
 *     }</pre></li>
 *   <li><strong>Conditional</strong> — when the candidate carries one or more
 *       {@code @ConditionalOnProperty} annotations:
 *       <pre>{@code
 *     private static final PropertyCondition[] ADMIN_RESOURCE_BINDING_CONDITIONS =
 *             new PropertyCondition[] {
 *         new PropertyCondition("adminApi.enabled", "true", false)
 *     };
 *
 *     @Provides @ElementsIntoSet @JaxRsResources
 *     static Set<Object> adminResourceBinding(@VertxConfig JsonObject config, Provider<AdminResource> provider) {
 *         return PropertyCondition.matchesAll(config, ADMIN_RESOURCE_BINDING_CONDITIONS)
 *                 ? Set.of(provider.get()) : Set.of();
 *     }
 *     }</pre></li>
 * </ul>
 *
 * <p>Both shapes accept the same parameters (a {@code @VertxConfig JsonObject} and a
 * {@code Provider<Resource>}) so the emitted module shape is uniform regardless of conditional
 * gating. Lazy {@link jakarta.inject.Provider} injection prevents Dagger from instantiating
 * resources whose conditions evaluate to {@code false} at startup (per NFR-CG011-002).
 *
 * <p>Repeatable {@code @ConditionalOnProperty} annotations are read via
 * {@link javax.lang.model.element.Element#getAnnotationMirrors()} so both the single form and the
 * {@code @ConditionalOnProperties} container form are handled correctly. {@code Element#getAnnotation}
 * is intentionally NOT used because it returns {@code null} when only the container is present —
 * a common footgun that would silently skip multi-condition resources.
 *
 * <p>When {@code diCandidates} is empty, no module is written. When the package cannot be
 * resolved (disjoint packages and no {@code -Avertique.codegen.package} override),
 * {@link PackageResolver#resolve} has already emitted a compiler diagnostic and this emitter
 * aborts silently.
 *
 */
public final class GeneratedJaxRsResourcesModuleEmitter {

    // --- Constants ---

    /** Simple class name of the generated module — must stay in sync with the CG-002 output. */
    static final String MODULE_SIMPLE_NAME = "GeneratedJaxRsResourcesModule";

    private static final String JAX_RS_RESOURCES_FQN = "dev.vertique.rest.core.dagger.JaxRsResources";

    private static final ClassName OBJECT = ClassName.get("java.lang", "Object");
    private static final ClassName SET = ClassName.get("java.util", "Set");
    private static final ClassName JAX_RS_RESOURCES = ClassName.bestGuess(JAX_RS_RESOURCES_FQN);
    private static final ClassName VERTX_CONFIG = ClassName.get("dev.vertique.core", "VertxConfig");
    private static final ClassName JSON_OBJECT = ClassName.get("io.vertx.core.json", "JsonObject");
    private static final ClassName PROVIDER = ClassName.get("jakarta.inject", "Provider");

    // --- State ---

    private final CodegenContext ctx;
    private final PackageResolver packageResolver;

    // --- Constructor ---

    /**
     * Creates a new emitter bound to the given codegen context and package resolver.
     *
     * @param ctx             the shared codegen context; must not be {@code null}
     * @param packageResolver the package resolver used to derive the output package;
     *                        must not be {@code null}
     */
    public GeneratedJaxRsResourcesModuleEmitter(CodegenContext ctx, PackageResolver packageResolver) {
        this.ctx = ctx;
        this.packageResolver = packageResolver;
    }

    // --- Public API ---

    /**
     * Writes {@code GeneratedJaxRsResourcesModule} for the given DI-eligible resource types.
     *
     * <p>If {@code diCandidates} is empty, this method returns immediately without writing
     * anything. Otherwise the output package is resolved via {@link PackageResolver#resolve};
     * if resolution fails (disjoint packages), the resolver has already emitted a diagnostic
     * and this method returns without writing.
     *
     * @param diCandidates the set of type elements for which to emit binding methods;
     *                     must not be {@code null}
     */
    public void emit(Set<TypeElement> diCandidates) {
        if (diCandidates.isEmpty()) {
            return;
        }

        String packageName = packageResolver.resolve(diCandidates.stream().toList(), ctx);
        if (packageName == null) {
            return;
        }

        ClassName moduleName = ClassName.get(packageName, MODULE_SIMPLE_NAME);
        DaggerModuleWriter writer = DaggerModuleWriter.named(moduleName).concrete();
        Conditions conditions = new Conditions(ctx.annotations());

        for (TypeElement candidate : diCandidates) {
            String methodName = bindingMethodName(candidate.getSimpleName().toString());
            // ClassName.get(TypeElement) yields the source-form nested name when the resource is a
            // static nested class. JaxRsCandidateScanner only surfaces top-level root elements
            // today; using the element-aware overload keeps the four CG-010 emitters consistent.
            ClassName implType = ClassName.get(candidate);
            List<Conditions.ConditionData> conditionData = conditions.read(candidate);

            ParameterSpec configParam = ParameterSpec.builder(JSON_OBJECT, "config")
                    .addAnnotation(AnnotationSpec.builder(VERTX_CONFIG).build())
                    .build();
            ParameterSpec providerParam = ParameterSpec.builder(
                            ParameterizedTypeName.get(PROVIDER, implType), "provider")
                    .build();

            CodeBlock body;
            if (conditionData.isEmpty()) {
                body = CodeBlock.of("return $T.of(provider.get());\n", SET);
            } else {
                String constantName = Conditions.constantName(methodName);
                writer.addStaticFinalField(
                        constantName,
                        ArrayTypeName.of(Conditions.PROPERTY_CONDITION),
                        Conditions.arrayInitializer(conditionData));
                body = CodeBlock.builder()
                        .add(
                                "return $T.matchesAll(config, $L) ? $T.of(provider.get()) : $T.of();\n",
                                Conditions.PROPERTY_CONDITION,
                                constantName,
                                SET,
                                SET)
                        .build();
            }

            writer.addElementsIntoSetProvides(JAX_RS_RESOURCES, OBJECT, methodName, body, configParam, providerParam);
        }

        JavaFile file = writer.build();
        try {
            file.writeTo(ctx.filer());
        } catch (IOException e) {
            ctx.diagnostics()
                    .error(
                            null,
                            "Failed to write generated source file '%s': %s",
                            moduleName.canonicalName(),
                            e.getMessage());
        }
    }

    // --- Internal helpers ---

    /**
     * Derives a camelCase method name from a resource type's simple class name by decapitalizing
     * it and appending {@code "Binding"}. Acronym-leading names keep their casing
     * ({@code URLProvider} → {@code URLProviderBinding}); reserved words are suffixed with
     * {@code _}.
     */
    private static String bindingMethodName(String simpleName) {
        String decap = Introspector.decapitalize(simpleName) + "Binding";
        return SourceVersion.isName(decap) ? decap : decap + "_";
    }
}
