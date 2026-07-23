// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.ParameterSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import dev.vertique.codegen.dagger.DaggerModuleWriter;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DaggerModuleWriter}.
 *
 * <p>Each test snapshots the full {@code JavaFile.toString()} output so any future change to
 * the emit logic produces a clear diff rather than a silent behavior change. The expected strings
 * are a stable part of the CG-001 contract.
 */
class DaggerModuleWriterTest {

    private static final ClassName MODULE_NAME = ClassName.get("com.example", "GeneratedModule");

    // --- @IntoSet scenario ---

    @Test
    void intoSetProvides_withoutQualifier_snapshot() {
        ClassName producedType = ClassName.get("com.example", "MyService");
        ClassName implType = ClassName.get("com.example", "MyServiceImpl");

        JavaFile file = DaggerModuleWriter.named(MODULE_NAME)
                .addIntoSetProvides(null, producedType, "myService", implType)
                .build();

        String expected = """
                package com.example;

                import dagger.Module;
                import dagger.Provides;
                import dagger.multibindings.IntoSet;

                @Module
                public abstract class GeneratedModule {
                  @Provides
                  @IntoSet
                  static MyService myService(MyServiceImpl impl) {
                    return impl;
                  }
                }
                """;
        assertEquals(expected, file.toString());
    }

    @Test
    void intoSetProvides_withQualifier_snapshot() {
        ClassName qualifier = ClassName.get("com.example", "MyQualifier");
        ClassName producedType = ClassName.get("com.example", "MyService");
        ClassName implType = ClassName.get("com.example", "MyServiceImpl");

        JavaFile file = DaggerModuleWriter.named(MODULE_NAME)
                .addIntoSetProvides(qualifier, producedType, "myService", implType)
                .build();

        String expected = """
                package com.example;

                import dagger.Module;
                import dagger.Provides;
                import dagger.multibindings.IntoSet;

                @Module
                public abstract class GeneratedModule {
                  @Provides
                  @IntoSet
                  @MyQualifier
                  static MyService myService(MyServiceImpl impl) {
                    return impl;
                  }
                }
                """;
        assertEquals(expected, file.toString());
    }

    // --- @Singleton scenario ---

    @Test
    void singletonProvides_snapshot() {
        ClassName producedType = ClassName.get("com.example", "MyService");
        ParameterSpec dep = ParameterSpec.builder(ClassName.get("com.example", "Dep"), "dep")
                .build();
        CodeBlock body = CodeBlock.of("return new $T(dep);", producedType);

        JavaFile file = DaggerModuleWriter.named(MODULE_NAME)
                .addSingletonProvides(producedType, "myService", body, dep)
                .build();

        String expected = """
                package com.example;

                import dagger.Module;
                import dagger.Provides;
                import jakarta.inject.Singleton;

                @Module
                public abstract class GeneratedModule {
                  @Provides
                  @Singleton
                  static MyService myService(Dep dep) {
                    return new MyService(dep);
                  }
                }
                """;
        assertEquals(expected, file.toString());
    }

    // --- @BindsOptionalOf scenario ---

    @Test
    void bindsOptionalOf_snapshot() {
        ClassName optionalType = ClassName.get("com.example", "OptionalService");

        JavaFile file = DaggerModuleWriter.named(MODULE_NAME)
                .addBindsOptionalOf(optionalType)
                .build();

        String expected = """
                package com.example;

                import dagger.BindsOptionalOf;
                import dagger.Module;

                @Module
                public abstract class GeneratedModule {
                  @BindsOptionalOf
                  abstract OptionalService optionalService();
                }
                """;
        assertEquals(expected, file.toString());
    }

    // --- Acronym-leading method name (Introspector.decapitalize) ---

    @Test
    void bindsOptionalOf_acronymLeadingName_preservesCapitals() {
        ClassName urlProvider = ClassName.get("com.example", "URLProvider");

        JavaFile file = DaggerModuleWriter.named(MODULE_NAME)
                .addBindsOptionalOf(urlProvider)
                .build();

        // Per Introspector.decapitalize: leading consecutive capitals stay capitalized.
        assertEquals(true, file.toString().contains("abstract URLProvider URLProvider();"));
    }

    // --- concrete() guard ---

    @Test
    void addBindsOptionalOf_onConcreteWriter_throwsIllegalState() {
        DaggerModuleWriter writer = DaggerModuleWriter.named(MODULE_NAME).concrete();

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class, () -> writer.addBindsOptionalOf(ClassName.get("com.example", "X")));
    }

    /** Reverse-order guard: concrete() called AFTER addBindsOptionalOf must also fail at build(). */
    @Test
    void buildFailsWhenConcreteFollowsAbstractBinding() {
        DaggerModuleWriter writer = DaggerModuleWriter.named(MODULE_NAME)
                .addBindsOptionalOf(ClassName.get("com.example", "X"))
                .concrete();

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, writer::build);
    }

    /** Reserved-keyword type names get an underscore suffix to keep the emitted source compilable. */
    @Test
    void bindsOptionalOf_keywordTypeName_getsUnderscoreSuffix() {
        ClassName classType = ClassName.get("com.example", "Class");

        JavaFile file = DaggerModuleWriter.named(MODULE_NAME)
                .addBindsOptionalOf(classType)
                .build();

        assertEquals(true, file.toString().contains("abstract Class class_();"));
    }

    // --- Combined scenario ---

    @Test
    void combinedModule_allThreePatterns() {
        ClassName serviceType = ClassName.get("com.example", "MyService");
        ClassName implType = ClassName.get("com.example", "MyServiceImpl");
        ClassName singletonType = ClassName.get("com.example", "MySingleton");
        ClassName optionalType = ClassName.get("com.example", "OptionalDep");
        ParameterSpec dep = ParameterSpec.builder(ClassName.get("com.example", "Dep"), "dep")
                .build();

        JavaFile file = DaggerModuleWriter.named(MODULE_NAME)
                .addIntoSetProvides(null, serviceType, "myService", implType)
                .addSingletonProvides(
                        singletonType, "mySingleton", CodeBlock.of("return new $T(dep);", singletonType), dep)
                .addBindsOptionalOf(optionalType)
                .build();

        String source = file.toString();
        // Verify all three methods appear
        assertEquals(true, source.contains("@IntoSet"), "Should contain @IntoSet");
        assertEquals(true, source.contains("@Singleton"), "Should contain @Singleton");
        assertEquals(true, source.contains("@BindsOptionalOf"), "Should contain @BindsOptionalOf");
    }

    // --- @ElementsIntoSet scenario ---

    @Test
    void elementsIntoSetProvides_withoutQualifier_snapshot() {
        ClassName serviceType = ClassName.get("com.example", "MyService");
        ClassName jsonObjectType = ClassName.get("io.vertx.core.json", "JsonObject");
        ClassName vertxConfigType = ClassName.get("dev.vertique.core", "VertxConfig");
        ClassName providerType = ClassName.get("jakarta.inject", "Provider");

        ParameterSpec configParam = ParameterSpec.builder(jsonObjectType, "config")
                .addAnnotation(AnnotationSpec.builder(vertxConfigType).build())
                .build();
        ParameterSpec providerParam = ParameterSpec.builder(
                        ParameterizedTypeName.get(providerType, ClassName.get("com.example", "MyServiceImpl")),
                        "provider")
                .build();

        CodeBlock body = CodeBlock.of("return $T.of(provider.get());", Set.class);

        JavaFile file = DaggerModuleWriter.named(MODULE_NAME)
                .addElementsIntoSetProvides(null, serviceType, "myService", body, configParam, providerParam)
                .build();

        String source = file.toString();
        assertEquals(true, source.contains("@ElementsIntoSet"), "Should contain @ElementsIntoSet");
        assertEquals(true, source.contains("Set<MyService>"), "Should contain Set<MyService> return type");
        assertEquals(true, source.contains("return Set.of(provider.get())"), "Should contain body");
        assertEquals(true, source.contains("@VertxConfig"), "Should contain qualifier annotation on param");
        assertEquals(true, source.contains("Provider<MyServiceImpl>"), "Should contain Provider param type");
    }

    @Test
    void elementsIntoSetProvides_withQualifier_snapshot() {
        ClassName qualifier = ClassName.get("com.example", "MyQualifier");
        ClassName serviceType = ClassName.get("com.example", "MyService");
        CodeBlock body = CodeBlock.of("return $T.of(provider.get());", Set.class);
        ParameterSpec providerParam = ParameterSpec.builder(
                        ParameterizedTypeName.get(
                                ClassName.get("jakarta.inject", "Provider"),
                                ClassName.get("com.example", "MyServiceImpl")),
                        "provider")
                .build();

        JavaFile file = DaggerModuleWriter.named(MODULE_NAME)
                .addElementsIntoSetProvides(qualifier, serviceType, "myService", body, providerParam)
                .build();

        String source = file.toString();
        assertEquals(true, source.contains("@ElementsIntoSet"), "Should contain @ElementsIntoSet");
        assertEquals(true, source.contains("@MyQualifier"), "Should contain qualifier on method");
        assertEquals(true, source.contains("Set<MyService>"), "Should contain Set<MyService> return type");
    }

    @Test
    void elementsIntoSetProvides_emptyBody_snapshot() {
        ClassName serviceType = ClassName.get("com.example", "MyService");
        CodeBlock body = CodeBlock.of("return $T.of();", Set.class);

        JavaFile file = DaggerModuleWriter.named(MODULE_NAME)
                .addElementsIntoSetProvides(null, serviceType, "myServiceEmpty", body)
                .build();

        String source = file.toString();
        assertEquals(true, source.contains("@ElementsIntoSet"), "Should contain @ElementsIntoSet");
        assertEquals(true, source.contains("Set<MyService>"), "Should contain Set<MyService> return type");
        assertEquals(true, source.contains("return Set.of()"), "Should contain empty-set body");
    }

    @Test
    void combinedModule_intoSet_elementsIntoSet_singleton() {
        ClassName serviceType = ClassName.get("com.example", "MyService");
        ClassName implType = ClassName.get("com.example", "MyServiceImpl");
        ClassName singletonType = ClassName.get("com.example", "MySingleton");
        ParameterSpec dep = ParameterSpec.builder(ClassName.get("com.example", "Dep"), "dep")
                .build();
        CodeBlock elementsBody = CodeBlock.of("return $T.of(impl);", Set.class);

        JavaFile file = DaggerModuleWriter.named(MODULE_NAME)
                .addIntoSetProvides(null, serviceType, "myServiceIntoSet", implType)
                .addElementsIntoSetProvides(null, serviceType, "myServiceElementsIntoSet", elementsBody, dep)
                .addSingletonProvides(
                        singletonType, "mySingleton", CodeBlock.of("return new $T(dep);", singletonType), dep)
                .build();

        String source = file.toString();
        assertEquals(true, source.contains("@IntoSet"), "Should contain @IntoSet");
        assertEquals(true, source.contains("@ElementsIntoSet"), "Should contain @ElementsIntoSet");
        assertEquals(true, source.contains("@Singleton"), "Should contain @Singleton");
        // Both @IntoSet and @ElementsIntoSet methods should be present
        assertEquals(true, source.contains("myServiceIntoSet"), "Should contain @IntoSet method name");
        assertEquals(true, source.contains("myServiceElementsIntoSet"), "Should contain @ElementsIntoSet method name");
    }

    // --- addBinds scenario (abstract @Binds with optional scope) ---

    private static final ClassName SINGLETON = ClassName.get("jakarta.inject", "Singleton");

    @Test
    void addBinds_emitsAbstractBindsWithScope() {
        ClassName boundType = ClassName.get("com.example", "MyBean");
        ClassName implType = ClassName.get("com.example", "MyBean$AopProxy");

        JavaFile file = DaggerModuleWriter.named(MODULE_NAME)
                .addBinds(boundType, implType, "bindMyBean", SINGLETON)
                .build();

        String expected = """
                package com.example;

                import dagger.Binds;
                import dagger.Module;
                import jakarta.inject.Singleton;

                @Module
                public abstract class GeneratedModule {
                  @Binds
                  @Singleton
                  abstract MyBean bindMyBean(MyBean$AopProxy impl);
                }
                """;
        assertEquals(expected, file.toString());
    }

    @Test
    void addBinds_noScope_emitsUnscopedBinds() {
        ClassName boundType = ClassName.get("com.example", "MyBean");
        ClassName implType = ClassName.get("com.example", "MyBean$AopProxy");

        JavaFile file = DaggerModuleWriter.named(MODULE_NAME)
                .addBinds(boundType, implType, "bindMyBean", null)
                .build();

        String source = file.toString();
        assertEquals(true, source.contains("@Binds"), "Should contain @Binds");
        assertEquals(true, source.contains("abstract MyBean bindMyBean("), "Should contain abstract binds method");
        assertEquals(
                true,
                source.contains("bindMyBean(MyBean$AopProxy impl)"),
                "A $-bearing top-level proxy name must be rendered verbatim, not as nested-class syntax");
        assertEquals(false, source.contains("@Singleton"), "Unscoped binds must carry no scope annotation");
        assertEquals(true, source.contains("public abstract class GeneratedModule"), "Module must stay abstract");
    }

    /** addBinds keeps the module abstract (mirrors the addBindsOptionalOf invariant). */
    @Test
    void addBinds_keepsModuleAbstract() {
        ClassName boundType = ClassName.get("com.example", "MyBean");
        ClassName implType = ClassName.get("com.example", "MyBeanImpl");

        JavaFile file = DaggerModuleWriter.named(MODULE_NAME)
                .addBinds(boundType, implType, "bindMyBean", null)
                .build();

        assertEquals(
                true,
                file.toString().contains("public abstract class GeneratedModule"),
                "A module with only addBinds calls must be emitted abstract");
    }

    /** addBinds after concrete() throws, mirroring addBindsOptionalOf. */
    @Test
    void addBinds_afterConcrete_throws() {
        DaggerModuleWriter writer = DaggerModuleWriter.named(MODULE_NAME).concrete();

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> writer.addBinds(
                        ClassName.get("com.example", "MyBean"),
                        ClassName.get("com.example", "MyBeanImpl"),
                        "bindMyBean",
                        null));
    }

    // --- @Generated marker scenario (generatedBy) ---

    @Test
    void generatedBy_rendersGeneratedMarkerOnModule() {
        ClassName boundType = ClassName.get("com.example", "MyBean");
        ClassName implType = ClassName.get("com.example", "MyBean$AopProxy");

        JavaFile file = DaggerModuleWriter.named(MODULE_NAME)
                .generatedBy("com.example.proc.MyProcessor")
                .addBinds(boundType, implType, "bindMyBean", null)
                .build();

        String expected = """
                package com.example;

                import dagger.Binds;
                import dagger.Module;
                import javax.annotation.processing.Generated;

                @Generated("com.example.proc.MyProcessor")
                @Module
                public abstract class GeneratedModule {
                  @Binds
                  abstract MyBean bindMyBean(MyBean$AopProxy impl);
                }
                """;
        assertEquals(expected, file.toString());
    }

    /** Without generatedBy, no @Generated marker is emitted — guards existing callers from regression. */
    @Test
    void withoutGeneratedBy_emitsNoGeneratedMarker() {
        ClassName boundType = ClassName.get("com.example", "MyBean");
        ClassName implType = ClassName.get("com.example", "MyBean$AopProxy");

        JavaFile file = DaggerModuleWriter.named(MODULE_NAME)
                .addBinds(boundType, implType, "bindMyBean", null)
                .build();

        String source = file.toString();
        assertEquals(false, source.contains("@Generated"), "Should not contain @Generated when generatedBy not called");
        assertEquals(
                false,
                source.contains("javax.annotation.processing.Generated"),
                "Should not import javax.annotation.processing.Generated when generatedBy not called");
    }

    // --- addStaticFinalField scenario ---

    @Test
    void staticFinalField_primitiveType_snapshot() {
        ClassName conditionType = ClassName.get("dev.vertique.core.config", "PropertyCondition");
        CodeBlock initializer = CodeBlock.of(
                "new $T[] { new $T($S, $S, false) }", conditionType, conditionType, "feature.enabled", "true");

        JavaFile file = DaggerModuleWriter.named(MODULE_NAME)
                .concrete()
                .addStaticFinalField(
                        "FEATURE_RESOURCE_BINDING_CONDITIONS",
                        com.palantir.javapoet.ArrayTypeName.of(conditionType),
                        initializer)
                .build();

        String source = file.toString();
        assertEquals(
                true,
                source.contains("private static final PropertyCondition[]"),
                "Should contain private static final PropertyCondition[] declaration");
        assertEquals(true, source.contains("FEATURE_RESOURCE_BINDING_CONDITIONS"), "Should contain the field name");
        assertEquals(
                true,
                source.contains("new PropertyCondition(\"feature.enabled\", \"true\", false)"),
                "Should contain the initializer expression");
    }

    @Test
    void staticFinalField_appearsBeforeMethods_inConcreteModule() {
        ClassName conditionType = ClassName.get("dev.vertique.core.config", "PropertyCondition");
        ClassName serviceType = ClassName.get("com.example", "MyService");
        CodeBlock fieldInit = CodeBlock.of("new $T[] {}", conditionType);
        CodeBlock body = CodeBlock.of("return $T.of();", Set.class);

        JavaFile file = DaggerModuleWriter.named(MODULE_NAME)
                .concrete()
                .addStaticFinalField("MY_CONDITIONS", com.palantir.javapoet.ArrayTypeName.of(conditionType), fieldInit)
                .addElementsIntoSetProvides(null, serviceType, "myService", body)
                .build();

        String source = file.toString();
        // Both the field and the method must be present
        assertEquals(true, source.contains("MY_CONDITIONS"), "Should contain field name");
        assertEquals(true, source.contains("@ElementsIntoSet"), "Should contain @ElementsIntoSet method");
        // Field must appear before the method in source order
        int fieldPos = source.indexOf("MY_CONDITIONS");
        int methodPos = source.indexOf("@ElementsIntoSet");
        assertEquals(true, fieldPos < methodPos, "Field should appear before method in generated source");
    }
}
