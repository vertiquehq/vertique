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
import dev.vertique.codegen.dagger.DaggerModuleWriter;
import dev.vertique.codegen.jaxrs.RestApplicationScanner;
import java.beans.Introspector;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;

/**
 * Emitter that writes each compilation unit's {@code GeneratedJaxRsResourcesModule}: the
 * presence-gated legacy {@code @JaxRsResources} binding and the lazy catalog entry for every
 * DI-eligible JAX-RS resource, and the native registration for every registered
 * {@code @RestApplication} declaration.
 *
 * <p>For each DI-eligible resource {@code R}, two bindings are produced, both feeding the
 * multibinding sets declared by {@code RestModule}:
 *
 * <ul>
 *   <li><strong>Presence-gated legacy binding</strong> — the
 *       {@code @Provides @ElementsIntoSet @JaxRsResources} method also takes the
 *       {@code @RestApplication} registration set and contributes {@code R} only when that set is
 *       empty (no declared applications — zero-declaration mode) and {@code R}'s conditions match:
 *       <pre>{@code
 *     @Provides @ElementsIntoSet @JaxRsResources
 *     static Set<Object> userResourceBinding(@VertxConfig JsonObject config,
 *             Set<GeneratedRestApplicationRegistration> applications, Provider<UserResource> provider) {
 *         return applications.isEmpty() ? Set.of(provider.get()) : Set.of();
 *     }
 *     }</pre></li>
 *   <li><strong>Lazy catalog entry</strong> — a {@code @Provides @IntoSet GeneratedJaxRsResourceEntry}
 *       method that carries {@code R}'s class, its evaluated condition result, and its
 *       {@code Provider}, and never calls the provider: <pre>{@code
 *     @Provides @IntoSet
 *     static GeneratedJaxRsResourceEntry userResourceEntry(@VertxConfig JsonObject config,
 *             Provider<UserResource> provider) {
 *         return GeneratedJaxRsResourceEntry.of(UserResource.class, true, provider);
 *     }
 *     }</pre></li>
 * </ul>
 *
 * <p>When a resource carries one or more {@code @ConditionalOnProperty} annotations, both bindings
 * share the same {@code private static final PropertyCondition[] X_BINDING_CONDITIONS} field,
 * declared once per resource:
 * <pre>{@code
 *     private static final PropertyCondition[] ADMIN_RESOURCE_BINDING_CONDITIONS =
 *             new PropertyCondition[] { new PropertyCondition("adminApi.enabled", "true", false) };
 *
 *     @Provides @ElementsIntoSet @JaxRsResources
 *     static Set<Object> adminResourceBinding(@VertxConfig JsonObject config,
 *             Set<GeneratedRestApplicationRegistration> applications, Provider<AdminResource> provider) {
 *         return applications.isEmpty() && PropertyCondition.matchesAll(config, ADMIN_RESOURCE_BINDING_CONDITIONS)
 *                 ? Set.of(provider.get()) : Set.of();
 *     }
 * }</pre>
 *
 * <p>For each registered {@code @RestApplication} declaration {@code D} (discovered and validated
 * by {@link RestApplicationScanner}), one {@code @Provides @IntoSet
 * GeneratedRestApplicationRegistration} method is emitted. It takes only the
 * {@code @VertxConfig JsonObject}, never a {@code Provider}, and never constructs {@code D}: the
 * declaring interface is named by its class literal only. The listed resources appear in the order
 * written (an empty list for a discovery declaration), the path is the normalized one, and
 * {@code openapiPath} is carried as written. The activation argument is {@code true}, or
 * {@code PropertyCondition.matchesAll(config, X_REGISTRATION_CONDITIONS)} when {@code D} carries
 * {@code @ConditionalOnProperty} (single or repeated):
 * <pre>{@code
 *     private static final PropertyCondition[] MGMT_API_REGISTRATION_CONDITIONS =
 *             new PropertyCondition[] { new PropertyCondition("mgmt.enabled", "true", false) };
 *
 *     @Provides @IntoSet
 *     static GeneratedRestApplicationRegistration mgmtApiRegistration(@VertxConfig JsonObject config) {
 *         return GeneratedRestApplicationRegistration.of(MgmtApi.class, "mgmt", "/api/mgmt",
 *                 List.of(OrderResource.class), false, "",
 *                 PropertyCondition.matchesAll(config, MGMT_API_REGISTRATION_CONDITIONS));
 *     }
 * }</pre>
 *
 * <p><strong>Method names.</strong> Every method is named by the decapitalized simple name of its
 * resource or declaring interface, plus {@code Binding}, {@code Entry}, or {@code Registration}.
 * The registration methods share one name counter per unit, named in the fully-qualified-name
 * order {@link RestApplicationScanner} sorted the declarations in; a base name already used by an
 * earlier registration method gets {@code _2}, {@code _3}, and so on.
 *
 * <p>When {@code diCandidates} and {@code declarations} are both empty, no module is written.
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
    private static final ClassName REST_APPLICATION_REGISTRATION =
            ClassName.get("dev.vertique.rest.jaxrs.runtime", "GeneratedRestApplicationRegistration");
    private static final ClassName LIST = ClassName.get("java.util", "List");
    private static final ClassName RESOURCE_ENTRY =
            ClassName.get("dev.vertique.rest.jaxrs.runtime", "GeneratedJaxRsResourceEntry");

    // --- State ---

    private final CodegenContext ctx;

    // --- Constructor ---

    /**
     * Creates a new emitter bound to the given codegen context.
     *
     * @param ctx the shared codegen context; must not be {@code null}
     */
    public GeneratedJaxRsResourcesModuleEmitter(CodegenContext ctx) {
        this.ctx = ctx;
    }

    // --- Public API ---

    /**
     * Writes {@code GeneratedJaxRsResourcesModule} in {@code packageName} for the given
     * DI-eligible resources and registered {@code @RestApplication} declarations.
     *
     * <p>The caller (the {@code JaxRsPipelineProcessor} pipeline) resolves {@code packageName}
     * from the resources when the unit has any, and otherwise from the declarations, so adding a
     * declaration never moves an existing module. When {@code diCandidates} and
     * {@code declarations} are both empty, this method returns immediately without writing
     * anything.
     *
     * @param packageName  the generated module's resolved package; must not be {@code null} when
     *                     either collection is non-empty
     * @param diCandidates the set of DI-eligible resource type elements; must not be {@code null}
     * @param declarations the registered {@code @RestApplication} declarations, in
     *                     fully-qualified-name order; must not be {@code null}
     */
    public void emit(
            String packageName, Set<TypeElement> diCandidates, List<RestApplicationScanner.Registration> declarations) {
        if (diCandidates.isEmpty() && declarations.isEmpty()) {
            return;
        }

        ClassName moduleName = ClassName.get(packageName, MODULE_SIMPLE_NAME);
        DaggerModuleWriter writer = DaggerModuleWriter.named(moduleName).concrete();
        Conditions conditions = new Conditions(ctx.annotations());

        ParameterSpec configParam = ParameterSpec.builder(JSON_OBJECT, "config")
                .addAnnotation(AnnotationSpec.builder(VERTX_CONFIG).build())
                .build();
        ParameterSpec applicationsParam = ParameterSpec.builder(
                        ParameterizedTypeName.get(SET, REST_APPLICATION_REGISTRATION), "applications")
                .build();

        for (TypeElement candidate : diCandidates) {
            emitResourceBindings(writer, conditions, configParam, applicationsParam, candidate);
        }

        Map<String, Integer> usedRegistrationNames = new HashMap<>();
        for (RestApplicationScanner.Registration declaration : declarations) {
            emitRestApplicationRegistration(writer, conditions, configParam, declaration, usedRegistrationNames);
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
     * Emits the presence-gated legacy binding and the lazy catalog entry for one DI-eligible
     * resource. Both share the same {@code X_BINDING_CONDITIONS} constant when the resource
     * carries {@code @ConditionalOnProperty}.
     */
    private void emitResourceBindings(
            DaggerModuleWriter writer,
            Conditions conditions,
            ParameterSpec configParam,
            ParameterSpec applicationsParam,
            TypeElement candidate) {
        String methodName = bindingMethodName(candidate.getSimpleName().toString());
        String entryMethodName = suffixedMethodName(candidate.getSimpleName().toString(), "Entry");
        // ClassName.get(TypeElement) yields the source-form nested name when the resource is a
        // static nested class. JaxRsCandidateScanner only surfaces top-level root elements
        // today; using the element-aware overload keeps every emitter in this pipeline consistent.
        ClassName implType = ClassName.get(candidate);
        List<Conditions.ConditionData> conditionData = conditions.read(candidate);

        ParameterSpec providerParam = ParameterSpec.builder(ParameterizedTypeName.get(PROVIDER, implType), "provider")
                .build();

        CodeBlock bindingBody;
        CodeBlock entryBody;
        if (conditionData.isEmpty()) {
            bindingBody = CodeBlock.of("return applications.isEmpty() ? $T.of(provider.get()) : $T.of();\n", SET, SET);
            entryBody = CodeBlock.of("return $T.of($T.class, true, provider);\n", RESOURCE_ENTRY, implType);
        } else {
            String constantName = Conditions.constantName(methodName);
            writer.addStaticFinalField(
                    constantName,
                    ArrayTypeName.of(Conditions.PROPERTY_CONDITION),
                    Conditions.arrayInitializer(conditionData));
            bindingBody = CodeBlock.builder()
                    .add(
                            "return applications.isEmpty() && $T.matchesAll(config, $L)"
                                    + " ? $T.of(provider.get()) : $T.of();\n",
                            Conditions.PROPERTY_CONDITION,
                            constantName,
                            SET,
                            SET)
                    .build();
            // The entry reuses the binding's own X_BINDING_CONDITIONS constant rather than
            // declaring a second one, mirroring the hand-written module in the unita fixture.
            entryBody = CodeBlock.of(
                    "return $T.of($T.class, $T.matchesAll(config, $L), provider);\n",
                    RESOURCE_ENTRY,
                    implType,
                    Conditions.PROPERTY_CONDITION,
                    constantName);
        }

        writer.addElementsIntoSetProvides(
                JAX_RS_RESOURCES, OBJECT, methodName, bindingBody, configParam, applicationsParam, providerParam);
        writer.addIntoSetProvidesWithBody(RESOURCE_ENTRY, entryMethodName, entryBody, configParam, providerParam);
    }

    /**
     * Emits one {@code @Provides @IntoSet GeneratedRestApplicationRegistration} method for a
     * registered {@code @RestApplication} declaration, appending {@code _2}, {@code _3}, and so on
     * when its base method name collides with an earlier registration method in this unit. The
     * method takes only the {@code @VertxConfig JsonObject} and names the declaring interface by its
     * class literal only.
     */
    private void emitRestApplicationRegistration(
            DaggerModuleWriter writer,
            Conditions conditions,
            ParameterSpec configParam,
            RestApplicationScanner.Registration declaration,
            Map<String, Integer> usedRegistrationNames) {
        TypeElement declaringType = declaration.type();
        String methodName = registrationMethodName(declaringType.getSimpleName().toString(), usedRegistrationNames);
        List<Conditions.ConditionData> conditionData = conditions.read(declaringType);

        List<CodeBlock> resourceLiterals = new ArrayList<>();
        for (TypeElement resource : declaration.resources()) {
            resourceLiterals.add(CodeBlock.of("$T.class", ClassName.get(resource)));
        }

        CodeBlock active;
        if (conditionData.isEmpty()) {
            active = CodeBlock.of("true");
        } else {
            String constantName = Conditions.constantName(methodName);
            writer.addStaticFinalField(
                    constantName,
                    ArrayTypeName.of(Conditions.PROPERTY_CONDITION),
                    Conditions.arrayInitializer(conditionData));
            active = CodeBlock.of("$T.matchesAll(config, $L)", Conditions.PROPERTY_CONDITION, constantName);
        }

        CodeBlock body = CodeBlock.of(
                "return $T.of($T.class, $S, $S, $T.of($L), $L, $S, $L);\n",
                REST_APPLICATION_REGISTRATION,
                ClassName.get(declaringType),
                declaration.name(),
                declaration.normalizedPath(),
                LIST,
                CodeBlock.join(resourceLiterals, ", "),
                declaration.discover(),
                declaration.openapiPath(),
                active);

        writer.addIntoSetProvidesWithBody(REST_APPLICATION_REGISTRATION, methodName, body, configParam);
    }

    /**
     * Derives a camelCase method name from a resource type's simple class name by decapitalizing
     * it and appending {@code "Binding"}. Acronym-leading names keep their casing
     * ({@code URLProvider} → {@code URLProviderBinding}); reserved words are suffixed with
     * {@code _}.
     */
    private static String bindingMethodName(String simpleName) {
        return suffixedMethodName(simpleName, "Binding");
    }

    /**
     * Derives a camelCase method name from a resource or declaring interface's simple class name by
     * decapitalizing it and appending {@code suffix}. Acronym-leading names keep their casing;
     * reserved words are suffixed with {@code _}.
     */
    private static String suffixedMethodName(String simpleName, String suffix) {
        String decap = Introspector.decapitalize(simpleName) + suffix;
        return SourceVersion.isName(decap) ? decap : decap + "_";
    }

    /**
     * Derives the {@code Registration}-suffixed method name for a declaring interface's simple class
     * name, appending {@code _2}, {@code _3}, and so on when {@code usedBaseNames} shows the base
     * name was already used by an earlier registration method in this unit.
     */
    private static String registrationMethodName(String simpleName, Map<String, Integer> usedBaseNames) {
        String base = suffixedMethodName(simpleName, "Registration");
        int count = usedBaseNames.merge(base, 1, Integer::sum);
        return count == 1 ? base : base + "_" + count;
    }
}
