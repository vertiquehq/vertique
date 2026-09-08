// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.dagger;

import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import java.beans.Introspector;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Modifier;

/**
 * INTERNAL framework seam — processor-authoring substrate consumed by sibling framework modules;
 * not an application contract and outside the maturity promise. An application uses the wiring
 * annotations this module documents and never calls this type.
 *
 * <p>Builder-style JavaPoet wrapper for the most common Dagger {@code @Module} emit patterns.
 *
 * <p>Supports the following binding types used throughout the Vertique framework:
 * <ul>
 *   <li>{@link #addIntoSetProvides} — a static {@code @Provides @IntoSet} method contributing an
 *       implementation to a multibinding set, optionally with a qualifier annotation
 *   <li>{@link #addElementsIntoSetProvides} — a static {@code @Provides @ElementsIntoSet} method
 *       returning a {@code Set<T>} that contributes zero or more elements to a multibinding set,
 *       enabling conditional registration where the body returns {@code Set.of()} or
 *       {@code Set.of(provider.get())} based on a runtime guard
 *   <li>{@link #addSingletonProvides} — a static {@code @Provides @Singleton} method with an
 *       arbitrary {@link CodeBlock} body and parameter list
 *   <li>{@link #addBindsOptionalOf} — an abstract {@code @BindsOptionalOf} declaration for
 *       optional bindings
 *   <li>{@link #addBinds} — an abstract {@code @Binds} declaration binding an interface/supertype to
 *       an implementation, optionally scoped
 *   <li>{@link #addMultibinds} — an abstract {@code @Multibinds} declaration that tells Dagger a
 *       multibinding set is intentionally empty when no {@code @IntoSet} contributors exist; required
 *       whenever a {@code Set<T>} is injected but zero contributors may be registered
 *   <li>{@link #addIntoSetProvidesWithBody} — a static {@code @Provides @IntoSet} method with a
 *       caller-supplied body; use when the contribution requires more than a simple {@code return impl}
 *       (e.g., constructing an {@code ObserverRegistration} with a lambda)
 *   <li>{@link #addStaticFinalField} — a {@code private static final} field with a caller-supplied
 *       initializer; used by conditional emitters to declare {@code PropertyCondition[]} constant arrays
 *       alongside their {@code @Provides @ElementsIntoSet} methods
 * </ul>
 *
 * <p>The generated module is {@code abstract} by default; call {@link #concrete()} to make it
 * concrete when all methods are static. All Dagger annotation class names are referenced by raw
 * {@link ClassName} rather than compile-time imports so that this writer does not depend on the
 * Dagger annotation processor being on the classpath.
 *
 * <p>Usage:
 * <pre>{@code
 * JavaFile file = DaggerModuleWriter.named(ClassName.get("com.example", "GeneratedModule"))
 *     .addIntoSetProvides(
 *         MY_QUALIFIER,
 *         ClassName.get("com.example", "MyService"),
 *         "myService",
 *         ClassName.get("com.example", "MyServiceImpl"))
 *     .addBindsOptionalOf(ClassName.get("com.example", "OptionalDep"))
 *     .build();
 * }</pre>
 */
public final class DaggerModuleWriter {

    // --- Dagger ClassName constants ---

    /** {@code dagger.Module} */
    private static final ClassName DAGGER_MODULE = ClassName.get("dagger", "Module");

    /** {@code dagger.Provides} */
    private static final ClassName DAGGER_PROVIDES = ClassName.get("dagger", "Provides");

    /** {@code dagger.multibindings.IntoSet} */
    private static final ClassName DAGGER_INTO_SET = ClassName.get("dagger.multibindings", "IntoSet");

    /** {@code dagger.multibindings.ElementsIntoSet} */
    private static final ClassName DAGGER_ELEMENTS_INTO_SET = ClassName.get("dagger.multibindings", "ElementsIntoSet");

    /** {@code dagger.BindsOptionalOf} */
    private static final ClassName DAGGER_BINDS_OPTIONAL_OF = ClassName.get("dagger", "BindsOptionalOf");

    /** {@code dagger.Binds} */
    private static final ClassName DAGGER_BINDS = ClassName.get("dagger", "Binds");

    /** {@code dagger.multibindings.Multibinds} */
    private static final ClassName DAGGER_MULTIBINDS = ClassName.get("dagger.multibindings", "Multibinds");

    /** {@code jakarta.inject.Singleton} */
    private static final ClassName SINGLETON = ClassName.get("jakarta.inject", "Singleton");

    /** {@code javax.annotation.processing.Generated} */
    private static final ClassName GENERATED = ClassName.get("javax.annotation.processing", "Generated");

    private final ClassName moduleName;
    private final List<FieldSpec> fields = new ArrayList<>();
    private final List<MethodSpec> methods = new ArrayList<>();
    private boolean concrete = false;
    private String generatedByProcessorFqn = null;

    /**
     * Private constructor; use {@link #named(ClassName)} factory method.
     *
     * @param moduleName the fully-qualified class name for the generated module
     */
    private DaggerModuleWriter(ClassName moduleName) {
        this.moduleName = moduleName;
    }

    /**
     * Creates a new {@code DaggerModuleWriter} for a module with the given class name.
     *
     * <p>The module will be {@code abstract} unless {@link #concrete()} is called.
     *
     * @param generatedModuleName the fully-qualified class name for the generated {@code @Module};
     *                            must not be {@code null}
     * @return a new writer instance
     */
    public static DaggerModuleWriter named(ClassName generatedModuleName) {
        return new DaggerModuleWriter(generatedModuleName);
    }

    /**
     * Switches the generated module from {@code abstract} (the default) to {@code concrete}.
     *
     * <p>Use this when all methods are static {@code @Provides} methods and no abstract
     * {@code @BindsOptionalOf} declarations are added.
     *
     * @return this writer for chaining
     */
    public DaggerModuleWriter concrete() {
        this.concrete = true;
        return this;
    }

    /**
     * Records the annotation processor that produces this module so that {@link #build()} renders a
     * class-level {@code @javax.annotation.processing.Generated("<processorFqn>")} marker on the
     * generated {@code @Module} type.
     *
     * <p>This matches the {@code @Generated} marker emitted by the sibling module emitters
     * (e.g. {@code ContributorModuleEmitter}, {@code BindingMetaEmitter}, {@code ProxyEmitter}),
     * which all reference {@link javax.annotation.processing.Generated}. When this method is never
     * called, {@link #build()} emits no {@code @Generated} annotation, leaving existing callers
     * unchanged.
     *
     * @param processorFqn the fully-qualified class name of the annotation processor generating this
     *                     module (e.g. {@code dev.vertique.codegen.aop.AopProcessor}); must not be
     *                     {@code null}
     * @return this writer for chaining
     */
    public DaggerModuleWriter generatedBy(String processorFqn) {
        this.generatedByProcessorFqn = processorFqn;
        return this;
    }

    /**
     * Adds a static {@code @Provides @IntoSet} method that contributes an implementation to a
     * multibinding set.
     *
     * <p>The generated method has the signature:
     * <pre>{@code
     * @Provides
     * @IntoSet
     * [@Qualifier]
     * static ProducedType methodName(ImplType impl) { return impl; }
     * }</pre>
     *
     * @param qualifier    an optional qualifier annotation to place on the method; pass
     *                     {@code null} to omit
     * @param producedType the set element type (the return type of the provides method); must not
     *                     be {@code null}
     * @param methodName   the Java method name to emit; must not be {@code null}
     * @param dependencyImpl the type of the parameter injected by Dagger; must not be
     *                       {@code null}
     * @return this writer for chaining
     */
    public DaggerModuleWriter addIntoSetProvides(
            ClassName qualifier, ClassName producedType, String methodName, ClassName dependencyImpl) {
        MethodSpec.Builder method = MethodSpec.methodBuilder(methodName)
                .addModifiers(Modifier.STATIC)
                .addAnnotation(AnnotationSpec.builder(DAGGER_PROVIDES).build())
                .addAnnotation(AnnotationSpec.builder(DAGGER_INTO_SET).build())
                .returns(producedType)
                .addParameter(dependencyImpl, "impl")
                .addStatement("return impl");

        if (qualifier != null) {
            method.addAnnotation(AnnotationSpec.builder(qualifier).build());
        }

        methods.add(method.build());
        return this;
    }

    /**
     * Adds a static {@code @Provides @Singleton} method with a custom body.
     *
     * <p>The generated method has the signature:
     * <pre>{@code
     * @Provides
     * @Singleton
     * static ProducedType methodName(dep0Type dep0Name, ...) { <body> }
     * }</pre>
     *
     * @param producedType the return type of the provides method; must not be {@code null}
     * @param methodName   the Java method name to emit; must not be {@code null}
     * @param body         the method body as a {@link CodeBlock}; must not be {@code null}
     * @param dependencies parameter specifications for the method's injected parameters
     * @return this writer for chaining
     */
    public DaggerModuleWriter addSingletonProvides(
            ClassName producedType, String methodName, CodeBlock body, ParameterSpec... dependencies) {
        MethodSpec.Builder method = MethodSpec.methodBuilder(methodName)
                .addModifiers(Modifier.STATIC)
                .addAnnotation(AnnotationSpec.builder(DAGGER_PROVIDES).build())
                .addAnnotation(AnnotationSpec.builder(SINGLETON).build())
                .returns(producedType)
                .addCode(body);

        for (ParameterSpec dep : dependencies) {
            method.addParameter(dep);
        }

        methods.add(method.build());
        return this;
    }

    /**
     * Adds a static {@code @Provides @ElementsIntoSet} method that contributes a {@link Set} of
     * elements to a Dagger multibinding set.
     *
     * <p>Unlike {@link #addIntoSetProvides}, which contributes a single element via a simple
     * delegation, this method accepts a caller-supplied {@link CodeBlock} body and allows the
     * generated method to contribute zero or more elements dynamically — enabling the
     * conditional-registration pattern where the body evaluates a
     * {@code PropertyCondition.matchesAll(...)}
     * guard and returns either {@code Set.of(provider.get())} or {@code Set.of()}.
     *
     * <p>The generated method has the signature:
     * <pre>{@code
     * @Provides
     * @ElementsIntoSet
     * [@Qualifier]
     * static Set<SetElementType> methodName(dep0Type dep0Name, ...) { <body> }
     * }</pre>
     *
     * @param qualifier      an optional qualifier annotation to place on the method; pass
     *                       {@code null} to omit
     * @param setElementType the element type of the {@link Set} returned by the method (the set
     *                       element type, not the {@code Set} itself); must not be {@code null}
     * @param methodName     the Java method name to emit; must not be {@code null}
     * @param body           the method body as a {@link CodeBlock}; must not be {@code null}
     * @param dependencies   parameter specifications for the method's injected parameters; may
     *                       be empty
     * @return this writer for chaining
     */
    public DaggerModuleWriter addElementsIntoSetProvides(
            ClassName qualifier,
            ClassName setElementType,
            String methodName,
            CodeBlock body,
            ParameterSpec... dependencies) {
        MethodSpec.Builder method = MethodSpec.methodBuilder(methodName)
                .addModifiers(Modifier.STATIC)
                .addAnnotation(AnnotationSpec.builder(DAGGER_PROVIDES).build())
                .addAnnotation(AnnotationSpec.builder(DAGGER_ELEMENTS_INTO_SET).build())
                .returns(ParameterizedTypeName.get(ClassName.get(Set.class), setElementType))
                .addCode(body);

        if (qualifier != null) {
            method.addAnnotation(AnnotationSpec.builder(qualifier).build());
        }

        for (ParameterSpec dep : dependencies) {
            method.addParameter(dep);
        }

        methods.add(method.build());
        return this;
    }

    /**
     * Adds a {@code private static final} field with the given name, type, and initializer to the
     * generated module class.
     *
     * <p>Fields are emitted in the order they are added, and always before methods, following the
     * standard Java field-then-method ordering convention. This method is used by conditional
     * emitters to declare {@code PropertyCondition[]} constant arrays alongside their
     * {@code @Provides @ElementsIntoSet} methods, for example:
     * <pre>{@code
     * private static final PropertyCondition[] ADMIN_RESOURCE_BINDING_CONDITIONS =
     *         new PropertyCondition[] {
     *     new PropertyCondition("adminApi.enabled", "true", false)
     * };
     * }</pre>
     *
     * <p>Fields work in both abstract and concrete modules — this method does not check the
     * {@link #concrete()} flag.
     *
     * @param fieldName   the Java field name to emit (must be a valid Java identifier); must not
     *                    be {@code null}
     * @param fieldType   the declared type of the field as a {@link TypeName}; must not be
     *                    {@code null}
     * @param initializer the field initializer expression as a {@link CodeBlock}; must not be
     *                    {@code null}
     * @return this writer for chaining
     */
    public DaggerModuleWriter addStaticFinalField(String fieldName, TypeName fieldType, CodeBlock initializer) {
        FieldSpec field = FieldSpec.builder(fieldType, fieldName)
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                .initializer(initializer)
                .build();
        fields.add(field);
        return this;
    }

    /**
     * Adds an abstract {@code @BindsOptionalOf} declaration for the given type.
     *
     * <p>The generated method has the signature:
     * <pre>{@code
     * @BindsOptionalOf
     * abstract Type type();
     * }</pre>
     *
     * <p>The method name is derived from the simple class name via
     * {@link Introspector#decapitalize(String)} so that acronym-leading names
     * ({@code URLProvider} → {@code URLProvider}) keep their casing while normal names
     * ({@code MyService} → {@code myService}) are lower-cased.
     *
     * <p>Adding an abstract {@code @BindsOptionalOf} method requires the surrounding class to be
     * abstract; this method therefore rejects calls made after {@link #concrete()} with an
     * {@link IllegalStateException}.
     *
     * @param type the type to declare as an optional binding; must not be {@code null}
     * @return this writer for chaining
     * @throws IllegalStateException if {@link #concrete()} was previously called
     */
    public DaggerModuleWriter addBindsOptionalOf(ClassName type) {
        if (concrete) {
            throw new IllegalStateException(
                    "addBindsOptionalOf requires an abstract module — do not call concrete() on a "
                            + "writer that emits @BindsOptionalOf declarations");
        }
        String methodName = bindingMethodName(type.simpleName());

        MethodSpec method = MethodSpec.methodBuilder(methodName)
                .addModifiers(Modifier.ABSTRACT)
                .addAnnotation(AnnotationSpec.builder(DAGGER_BINDS_OPTIONAL_OF).build())
                .returns(type)
                .build();

        methods.add(method);
        return this;
    }

    /**
     * Adds an abstract {@code @Multibinds} declaration for the given set type.
     *
     * <p>A {@code @Multibinds} declaration tells Dagger that the named multibinding set may have zero
     * contributors — it is required whenever a {@code Set<T>} is injected via Dagger multibinding but
     * there may be compilation units in which no {@code @IntoSet} methods contribute to that set. Without
     * the declaration, Dagger reports a {@code MissingBinding} error for the empty-set case.
     *
     * <p>The generated method has the signature:
     * <pre>{@code
     * @Multibinds
     * abstract Set<ElementType> methodName();
     * }</pre>
     *
     * <p>As with {@link #addBindsOptionalOf}, this is an abstract method; calling {@link #concrete()}
     * before (or after) this method will result in a build-time error.
     *
     * @param setType    the full parameterized set type to declare, e.g.
     *                   {@code ParameterizedTypeName.get(Set.class, ObserverRegistration.class)};
     *                   must not be {@code null}
     * @param methodName the Java method name to emit; must not be {@code null}
     * @return this writer for chaining
     * @throws IllegalStateException if {@link #concrete()} was previously called
     */
    public DaggerModuleWriter addMultibinds(ParameterizedTypeName setType, String methodName) {
        if (concrete) {
            throw new IllegalStateException(
                    "addMultibinds requires an abstract module — do not call concrete() on a writer that "
                            + "emits @Multibinds declarations");
        }

        MethodSpec method = MethodSpec.methodBuilder(methodName)
                .addModifiers(Modifier.ABSTRACT)
                .addAnnotation(AnnotationSpec.builder(DAGGER_MULTIBINDS).build())
                .returns(setType)
                .build();

        methods.add(method);
        return this;
    }

    /**
     * Adds a static {@code @Provides @IntoSet} method with a caller-supplied body.
     *
     * <p>Use this overload when the contribution to a multibinding set requires more than a simple
     * {@code return impl} delegation — for example, constructing an {@link dev.vertique.events.ObserverRegistration}
     * with a priority value and a reflection-free lambda.
     *
     * <p>The generated method has the signature:
     * <pre>{@code
     * @Provides
     * @IntoSet
     * static ProducedType methodName(dep0Type dep0, ...) { <body> }
     * }</pre>
     *
     * @param producedType the set element type (the return type of the provides method); must not be
     *                     {@code null}
     * @param methodName   the Java method name to emit; must not be {@code null}
     * @param body         the method body as a {@link CodeBlock}; must not be {@code null}
     * @param dependencies parameter specifications for the method's injected parameters; may be empty
     * @return this writer for chaining
     */
    public DaggerModuleWriter addIntoSetProvidesWithBody(
            TypeName producedType, String methodName, CodeBlock body, ParameterSpec... dependencies) {
        MethodSpec.Builder method = MethodSpec.methodBuilder(methodName)
                .addModifiers(Modifier.STATIC)
                .addAnnotation(AnnotationSpec.builder(DAGGER_PROVIDES).build())
                .addAnnotation(AnnotationSpec.builder(DAGGER_INTO_SET).build())
                .returns(producedType)
                .addCode(body);

        for (ParameterSpec dep : dependencies) {
            method.addParameter(dep);
        }

        methods.add(method.build());
        return this;
    }

    /**
     * Adds an abstract {@code @Binds} method that binds {@code boundType} to {@code implType},
     * optionally scoped.
     *
     * <p>The generated method has the signature:
     * <pre>{@code
     * @Binds
     * [@Scope]
     * abstract BoundType methodName(ImplType impl);
     * }</pre>
     *
     * <p>The scope annotation is emitted only when {@code scopeOrNull} is non-null. As with
     * {@link #addBindsOptionalOf}, an abstract {@code @Binds} method requires the surrounding class to
     * be abstract; this method therefore rejects calls made after {@link #concrete()} with an
     * {@link IllegalStateException} and keeps the built module abstract.
     *
     * <p>Both {@code boundType} and {@code implType} are rendered <em>verbatim</em> as given — a
     * {@code $} in a simple name (e.g. a generated top-level AOP proxy literally named
     * {@code Greeter$AopProxy}) is a legal identifier character and is emitted unchanged. This helper
     * does <strong>not</strong> reinterpret a {@code $}-bearing simple name as nested-class syntax.
     *
     * @param boundType   the bound (returned) type; must not be {@code null}
     * @param implType    the implementation type injected as the method parameter; must not be
     *                    {@code null}
     * @param methodName  the Java method name to emit; must not be {@code null}
     * @param scopeOrNull an optional scope annotation (e.g. {@code jakarta.inject.Singleton}) to
     *                    place on the method; pass {@code null} for an unscoped binding
     * @return this writer for chaining
     * @throws IllegalStateException if {@link #concrete()} was previously called
     */
    public DaggerModuleWriter addBinds(
            ClassName boundType, ClassName implType, String methodName, ClassName scopeOrNull) {
        if (concrete) {
            throw new IllegalStateException(
                    "addBinds requires an abstract module — do not call concrete() on a writer that "
                            + "emits @Binds declarations");
        }

        MethodSpec.Builder method = MethodSpec.methodBuilder(methodName)
                .addModifiers(Modifier.ABSTRACT)
                .addAnnotation(AnnotationSpec.builder(DAGGER_BINDS).build())
                .returns(boundType)
                .addParameter(implType, "impl");

        if (scopeOrNull != null) {
            method.addAnnotation(AnnotationSpec.builder(scopeOrNull).build());
        }

        methods.add(method.build());
        return this;
    }

    /**
     * Adds an abstract {@code @Binds} method that binds a parameterized {@code boundType} to
     * {@code implType}, optionally scoped.
     *
     * <p>This overload accepts a {@link TypeName} for the bound type so that parameterized types
     * (e.g. {@code Event<OrderCreated>}) can be expressed in the return position of the generated
     * {@code @Binds} method. Use the {@link ClassName}-overload
     * ({@link #addBinds(ClassName, ClassName, String, ClassName)}) when the bound type is a raw
     * (non-parameterized) class.
     *
     * <p>The generated method has the signature:
     * <pre>{@code
     * @Binds
     * [@Scope]
     * abstract BoundType methodName(ImplType impl);
     * }</pre>
     *
     * @param boundType   the bound (returned) type as a {@link TypeName}, supporting parameterized
     *                    forms such as {@code ParameterizedTypeName.get(Event.class, X.class)};
     *                    must not be {@code null}
     * @param implType    the implementation type injected as the method parameter; must not be
     *                    {@code null}
     * @param methodName  the Java method name to emit; must not be {@code null}
     * @param scopeOrNull an optional scope annotation to place on the method; pass {@code null}
     *                    for an unscoped binding
     * @return this writer for chaining
     * @throws IllegalStateException if {@link #concrete()} was previously called
     */
    public DaggerModuleWriter addBinds(
            TypeName boundType, ClassName implType, String methodName, ClassName scopeOrNull) {
        if (concrete) {
            throw new IllegalStateException(
                    "addBinds requires an abstract module — do not call concrete() on a writer that "
                            + "emits @Binds declarations");
        }

        MethodSpec.Builder method = MethodSpec.methodBuilder(methodName)
                .addModifiers(Modifier.ABSTRACT)
                .addAnnotation(AnnotationSpec.builder(DAGGER_BINDS).build())
                .returns(boundType)
                .addParameter(implType, "impl");

        if (scopeOrNull != null) {
            method.addAnnotation(AnnotationSpec.builder(scopeOrNull).build());
        }

        methods.add(method.build());
        return this;
    }

    /**
     * Derives a Java method name from a type's simple name. Acronym-leading names keep their
     * casing via {@link Introspector#decapitalize(String)}; results that collide with a Java
     * keyword (e.g., type literally named {@code Class} → {@code class}) are suffixed with an
     * underscore so the emitted source is always a valid identifier.
     */
    private static String bindingMethodName(String simpleName) {
        String name = Introspector.decapitalize(simpleName);
        return SourceVersion.isName(name) ? name : name + "_";
    }

    /**
     * Assembles the accumulated method declarations into a {@link JavaFile} ready for writing via
     * {@link javax.annotation.processing.Filer#createSourceFile}.
     *
     * <p>When {@link #generatedBy(String)} was called, the generated type carries a class-level
     * {@code @javax.annotation.processing.Generated("<processorFqn>")} marker ahead of the
     * {@code @Module} annotation; otherwise no {@code @Generated} marker is emitted.
     *
     * @return a {@link JavaFile} containing the generated {@code @Module} class
     */
    public JavaFile build() {
        boolean hasAbstractMethod = methods.stream().anyMatch(m -> m.modifiers().contains(Modifier.ABSTRACT));
        if (concrete && hasAbstractMethod) {
            throw new IllegalStateException(
                    "Cannot build a concrete module containing abstract methods — drop concrete() or "
                            + "remove the abstract @BindsOptionalOf binding(s)");
        }

        TypeSpec.Builder typeBuilder =
                TypeSpec.classBuilder(moduleName.simpleName()).addModifiers(Modifier.PUBLIC);

        if (generatedByProcessorFqn != null) {
            typeBuilder.addAnnotation(AnnotationSpec.builder(GENERATED)
                    .addMember("value", "$S", generatedByProcessorFqn)
                    .build());
        }

        typeBuilder.addAnnotation(AnnotationSpec.builder(DAGGER_MODULE).build());

        if (!concrete) {
            typeBuilder.addModifiers(Modifier.ABSTRACT);
        }

        fields.forEach(typeBuilder::addField);
        methods.forEach(typeBuilder::addMethod);

        return JavaFile.builder(moduleName.packageName(), typeBuilder.build()).build();
    }
}
