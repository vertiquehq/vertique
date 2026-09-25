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
import dev.vertique.codegen.jaxrs.JaxRsApplicationScanner;
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
 * DI-eligible JAX-RS resource, and the application registration for every eligible
 * {@code jakarta.ws.rs.core.Application} subtype.
 *
 * <p>For each DI-eligible resource {@code R}, two bindings are produced, both feeding the
 * multibinding sets declared by {@code RestModule}:
 *
 * <ul>
 *   <li><strong>Presence-gated legacy binding</strong> — the existing
 *       {@code @Provides @ElementsIntoSet @JaxRsResources} method also takes the generated
 *       application-registration set and contributes {@code R} only when that set is empty (no
 *       declared applications — zero-declaration mode) and {@code R}'s conditions match: <pre>{@code
 *     @Provides @ElementsIntoSet @JaxRsResources
 *     static Set<Object> userResourceBinding(@VertxConfig JsonObject config,
 *             Set<GeneratedJaxRsApplicationRegistration> applications, Provider<UserResource> provider) {
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
 *             Set<GeneratedJaxRsApplicationRegistration> applications, Provider<AdminResource> provider) {
 *         return applications.isEmpty() && PropertyCondition.matchesAll(config, ADMIN_RESOURCE_BINDING_CONDITIONS)
 *                 ? Set.of(provider.get()) : Set.of();
 *     }
 * }</pre>
 *
 * <p>For each eligible application {@code A} (discovered and validated by
 * {@link JaxRsApplicationScanner}), one {@code @Provides @IntoSet GeneratedJaxRsApplicationRegistration}
 * method is emitted. It takes a {@code Provider<A>} when {@code A} is constructed through its
 * {@code @Inject} constructor, or no {@code Provider} parameter (using {@code A::new} as the
 * factory) otherwise:
 * <pre>{@code
 *     @Provides @IntoSet
 *     static GeneratedJaxRsApplicationRegistration publicApplicationRegistration(
 *             @VertxConfig JsonObject config, Provider<PublicApplication> provider) {
 *         return GeneratedJaxRsApplicationRegistration.of(
 *                 PublicApplication.class, "/api/public", true, provider);
 *     }
 * }</pre>
 *
 * <p><strong>Method names.</strong> Every method is named by the decapitalized simple name of its
 * resource or application, plus {@code Binding}, {@code Entry}, or {@code Registration}. When two
 * eligible applications in one unit share a simple name, {@code _2}, {@code _3}, and so on are
 * appended, in the fully-qualified-name order {@link JaxRsApplicationScanner} already sorted them
 * in.
 *
 * <p>When both {@code diCandidates} and {@code registrations} are empty, no module is written.
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
    private static final ClassName APPLICATION_REGISTRATION =
            ClassName.get("dev.vertique.rest.jaxrs.runtime", "GeneratedJaxRsApplicationRegistration");
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
     * DI-eligible resources and validated application registrations.
     *
     * <p>The caller (the {@code JaxRsPipelineProcessor} pipeline) resolves {@code packageName}
     * from the resources when the unit has any, and from the applications only in an
     * applications-only unit, so adding an {@code Application} never moves an existing module.
     * When both {@code diCandidates} and {@code registrations} are empty, this method returns
     * immediately without writing anything.
     *
     * @param packageName   the generated module's resolved package; must not be {@code null} when
     *                      either list is non-empty
     * @param diCandidates  the set of DI-eligible resource type elements; must not be {@code null}
     * @param registrations the validated application registrations, in fully-qualified-name
     *                      order; must not be {@code null}
     */
    public void emit(
            String packageName,
            Set<TypeElement> diCandidates,
            List<JaxRsApplicationScanner.Registration> registrations) {
        if (diCandidates.isEmpty() && registrations.isEmpty()) {
            return;
        }

        ClassName moduleName = ClassName.get(packageName, MODULE_SIMPLE_NAME);
        DaggerModuleWriter writer = DaggerModuleWriter.named(moduleName).concrete();
        Conditions conditions = new Conditions(ctx.annotations());

        ParameterSpec configParam = ParameterSpec.builder(JSON_OBJECT, "config")
                .addAnnotation(AnnotationSpec.builder(VERTX_CONFIG).build())
                .build();
        ParameterSpec applicationsParam = ParameterSpec.builder(
                        ParameterizedTypeName.get(SET, APPLICATION_REGISTRATION), "applications")
                .build();

        for (TypeElement candidate : diCandidates) {
            emitResourceBindings(writer, conditions, configParam, applicationsParam, candidate);
        }

        Map<String, Integer> usedRegistrationNames = new HashMap<>();
        for (JaxRsApplicationScanner.Registration registration : registrations) {
            emitApplicationRegistration(writer, conditions, configParam, registration, usedRegistrationNames);
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
     * Emits one {@code @Provides @IntoSet GeneratedJaxRsApplicationRegistration} method for a
     * validated application registration, appending {@code _2}, {@code _3}, and so on when its
     * base method name collides with an earlier one in this unit.
     */
    private void emitApplicationRegistration(
            DaggerModuleWriter writer,
            Conditions conditions,
            ParameterSpec configParam,
            JaxRsApplicationScanner.Registration registration,
            Map<String, Integer> usedRegistrationNames) {
        TypeElement applicationType = registration.type();
        String methodName =
                registrationMethodName(applicationType.getSimpleName().toString(), usedRegistrationNames);
        ClassName appType = ClassName.get(applicationType);
        List<Conditions.ConditionData> conditionData = conditions.read(applicationType);

        List<ParameterSpec> params = new ArrayList<>();
        params.add(configParam);

        CodeBlock.Builder body = CodeBlock.builder();
        body.add("return $T.of($T.class, $S, ", APPLICATION_REGISTRATION, appType, registration.normalizedPath());
        if (conditionData.isEmpty()) {
            body.add("true, ");
        } else {
            String constantName = Conditions.constantName(methodName);
            writer.addStaticFinalField(
                    constantName,
                    ArrayTypeName.of(Conditions.PROPERTY_CONDITION),
                    Conditions.arrayInitializer(conditionData));
            body.add("$T.matchesAll(config, $L), ", Conditions.PROPERTY_CONDITION, constantName);
        }
        if (registration.constructedByProvider()) {
            params.add(ParameterSpec.builder(ParameterizedTypeName.get(PROVIDER, appType), "provider")
                    .build());
            body.add("provider);\n");
        } else {
            body.add("$T::new);\n", appType);
        }

        writer.addIntoSetProvidesWithBody(
                APPLICATION_REGISTRATION, methodName, body.build(), params.toArray(new ParameterSpec[0]));
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
     * Derives a camelCase method name from a resource or application type's simple class name by
     * decapitalizing it and appending {@code suffix}. Acronym-leading names keep their casing;
     * reserved words are suffixed with {@code _}.
     */
    private static String suffixedMethodName(String simpleName, String suffix) {
        String decap = Introspector.decapitalize(simpleName) + suffix;
        return SourceVersion.isName(decap) ? decap : decap + "_";
    }

    /**
     * Derives the {@code Registration}-suffixed method name for an application's simple class
     * name, appending {@code _2}, {@code _3}, and so on when {@code usedBaseNames} shows the base
     * name was already used by an earlier application in this unit.
     */
    private static String registrationMethodName(String simpleName, Map<String, Integer> usedBaseNames) {
        String base = suffixedMethodName(simpleName, "Registration");
        int count = usedBaseNames.merge(base, 1, Integer::sum);
        return count == 1 ? base : base + "_" + count;
    }
}
