// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.aop;

import com.palantir.javapoet.ClassName;
import dev.vertique.aop.Aspect;
import dev.vertique.codegen.AnnotationMirrors;
import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.Diagnostics;
import dev.vertique.codegen.meta.AnnotationLiteralEmitter;
import dev.vertique.codegen.validate.InjectConstructorValidator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedOptions;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.ElementFilter;

/**
 * Annotation processor that generates a reflection-free {@code {Bean}$AopProxy extends Bean} for
 * each bean with at least one method carrying an {@link Aspect}-meta-annotated annotation, and a
 * single {@code GeneratedAopModule} Dagger {@code @Module} whose {@code @Binds} substitutes each
 * proxy for its bean (scope replicated from the bean's declared scope).
 *
 * <p>This class is registered via {@code META-INF/services/javax.annotation.processing.Processor}.
 *
 * <p><strong>Slice 1.2 scope:</strong> this implements the emission pipeline — trigger discovery,
 * bean collection, proxy + metadata + annotation-literal emission, and the generated module —
 * plus the binding-origin / proxyability <em>diagnostics</em> (FR-013-03, FR-013-03a). A bean
 * carrying an aspect-annotated method is a hard compile error when it:
 * <ul>
 *   <li>is constructed by {@code @AssistedInject} or carries {@code @Assisted} constructor
 *       parameters — such beans cannot be subclass-proxied;
 *   <li>has no {@code @Inject} constructor, or more than one (reuses
 *       {@link InjectConstructorValidator});
 *   <li>is also supplied by a user {@code @Provides} method in the same compilation unit — the
 *       generated {@code @Binds} would conflict with the user binding;
 *   <li>is not {@code public} — the generated {@code @Binds} in {@code GeneratedAopModule}
 *       references the bean type by name from a possibly-different-package module; a non-public
 *       bean yields uncompilable generated source.
 * </ul>
 * No invalid bean is ever silently skipped: every refusal raises a {@code Diagnostics.error}.
 */
@SupportedAnnotationTypes("*")
@SupportedSourceVersion(SourceVersion.RELEASE_21)
@SupportedOptions(CodegenContext.OPTION_OUTPUT_PACKAGE)
public final class AopProcessor extends AbstractProcessor {

    private static final String ASPECT_FQN = "dev.vertique.aop.Aspect";
    private static final String SCOPE_FQN = "jakarta.inject.Scope";
    private static final String JAVAX_SCOPE_FQN = "javax.inject.Scope";
    private static final String ASSISTED_INJECT_FQN = "dagger.assisted.AssistedInject";
    private static final String ASSISTED_FQN = "dagger.assisted.Assisted";
    private static final String PROVIDES_FQN = "dagger.Provides";
    private static final String FUTURE_FQN = "io.vertx.core.Future";

    private CodegenContext ctx;
    private AopProxyEmitter proxyEmitter;
    private GeneratedAopModuleEmitter moduleEmitter;
    private InjectConstructorValidator injectConstructorValidator;

    /**
     * FQNs of {@code <Ann>$AopLiteral} classes already written to the {@code Filer} this compilation.
     * The literal class is per-aspect-type (its package and shape are stable regardless of attribute
     * values), so two beans sharing the same aspect must write it once — a second write triggers a
     * Filer "Attempt to recreate a file" error (Bug F2). This set deduplicates the writes across the
     * per-bean {@link AopProxyEmitter#emit} calls.
     */
    private final Set<String> emittedLiteralFqns = new HashSet<>();

    /**
     * Guards single emission of {@code GeneratedAopModule}. The processor scans, emits proxies, and
     * emits the module in the <em>first</em> round that yields any binding — never in
     * {@code processingOver()} — so the generated module exists before Dagger's
     * {@code ComponentProcessingStep} validates a hand-written {@code @Component} that references it.
     * Emitting the module at {@code processingOver()} (as an earlier version did) is too late: Dagger
     * validates the component in an earlier round and fails to resolve the not-yet-generated module.
     * Mirrors {@code ServiceContractProcessor}'s first-round emission.
     */
    private boolean emitted = false;

    @Override
    public synchronized void init(ProcessingEnvironment env) {
        super.init(env);
        this.ctx = new CodegenContext(env);
        this.proxyEmitter = new AopProxyEmitter(ctx);
        this.moduleEmitter = new GeneratedAopModuleEmitter(ctx);
        this.injectConstructorValidator = new InjectConstructorValidator(ctx);
    }

    /**
     * Discovers aspect trigger annotations (those meta-annotated with {@link Aspect}), finds the
     * beans whose methods carry them, emits a proxy per bean, and emits one {@code GeneratedAopModule}
     * binding each proxy — in the first round that yields a binding, not the final
     * ({@code processingOver}) round, so the module exists before Dagger validates a referencing
     * {@code @Component} (see the {@code emitted} field).
     *
     * @param annotations the annotation types requested to be processed this round
     * @param roundEnv the environment for the current processing round
     * @return {@code false} — the processor never claims the round, so sibling processors still
     *     observe the same annotations
     */
    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        // The module is emitted once, in the first round that yields a binding (see the emitted
        // field's javadoc). Skip the final round and any round after emission so the @Binds-bearing
        // module exists before Dagger validates a component that references it.
        if (roundEnv.processingOver() || emitted) {
            return false;
        }

        // Identify the aspect trigger annotations available this round (meta-annotated with @Aspect).
        Set<TypeElement> triggers = new LinkedHashSet<>();
        for (TypeElement annotation : annotations) {
            if (annotation.getKind() == ElementKind.ANNOTATION_TYPE && isAspectTrigger(annotation)) {
                triggers.add(annotation);
            }
        }

        Set<String> aspectFqns = triggers.stream()
                .map(t -> t.getQualifiedName().toString())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        if (!triggers.isEmpty()) {
            // Collect the FQNs of bean types supplied by a user @Provides in this compilation unit.
            Set<String> userProvidedTypes = collectUserProvidedTypes(roundEnv);

            // Group aspect-annotated methods by their declaring bean.
            Map<TypeElement, List<ExecutableElement>> beans = collectBeans(triggers, roundEnv);
            for (Map.Entry<TypeElement, List<ExecutableElement>> entry : beans.entrySet()) {
                TypeElement bean = entry.getKey();

                // (1) @AssistedInject / @Assisted beans cannot be subclass-proxied — hard refuse.
                if (isAssisted(bean)) {
                    ctx.diagnostics()
                            .error(
                                    bean,
                                    "%s carries an aspect method but is constructed by @AssistedInject"
                                            + " (or has @Assisted constructor parameters); such beans cannot be"
                                            + " subclass-proxied for method AOP",
                                    bean.getQualifiedName());
                    continue;
                }

                // (2) Binding-origin: exactly one @Inject constructor (0 or >1 → hard error).
                if (!injectConstructorValidator.validate(bean)) {
                    continue;
                }

                // (3) A bean also supplied by a user @Provides in the same compilation is ambiguous —
                // the generated @Binds would collide with the user binding.
                if (userProvidedTypes.contains(bean.getQualifiedName().toString())) {
                    ctx.diagnostics()
                            .error(
                                    bean,
                                    "%s carries an aspect method but is also supplied by a user @Provides"
                                            + " method in the same compilation unit; remove the @Provides so the"
                                            + " generated AOP proxy can bind the type",
                                    bean.getQualifiedName());
                    continue;
                }

                // (4) An intercepted method returning a raw or wildcard Future cannot be proxied
                // safely: a raw Future is mis-classified as a synchronous return (so the around-chain
                // meters the wrong outcome), and a wildcard Future<? ...> would force the emitter to
                // render an invalid back-cast to the wildcard element type. Reject both — concrete
                // Future<X> support is the v1 contract.
                if (hasUnsupportedFutureReturn(bean, entry.getValue())) {
                    continue;
                }

                // (5) The subclass-proxy strategy cannot proxy a final class, nor a final / private /
                // static intercepted method, nor a method whose throws clause names a method type
                // variable (the sync guard would emit a non-reifiable instanceof E). Reject each with a
                // clear up-front diagnostic so the build fails with the AOP message rather than a
                // confusing generated-source javac error.
                if (isNotProxyable(bean, entry.getValue())) {
                    continue;
                }

                // (6) An aspect TRIGGER annotation with a member of an unsupported attribute kind
                // (char/float/double, a nested annotation, or an array of those) cannot be
                // materialized into the per-aspect <Ann>$AopLiteral the proxy bakes for it. Precheck the
                // aspect's annotation type up front so an unsupported kind is a clean Diagnostics.error
                // rather than an UnsupportedOperationException crash mid-emission in
                // AnnotationLiteralEmitter (P2-W2). Mirrors the method-annotation precheck in
                // AopProxyEmitter.materializeMethodAnnotations.
                if (hasUnsupportedAspectAttribute(entry.getValue(), aspectFqns)) {
                    continue;
                }

                Optional<ExecutableElement> injectCtor = ctx.injectConstructor(bean);
                ClassName proxy = proxyEmitter.emit(
                        bean, injectCtor.orElseThrow(), entry.getValue(), aspectFqns, emittedLiteralFqns);
                moduleEmitter.add(bean, proxy, scopeOf(bean));
            }
        }

        // Emit the single module in this first binding-yielding round so it exists before Dagger
        // validates a referencing @Component. Guard re-emission on later rounds via emitted.
        if (moduleEmitter.hasBindings()) {
            moduleEmitter.emit();
            emitted = true;
        }
        return false;
    }

    // --- diagnostic helpers ---

    /**
     * Returns {@code true} when the bean is constructed by {@code @AssistedInject} or declares any
     * {@code @Assisted} constructor parameter. Such beans are built by a generated Dagger factory,
     * not by direct construction, so the subclass-proxy strategy cannot replicate their wiring.
     *
     * @param bean the candidate aspect bean
     * @return {@code true} if the bean uses assisted injection
     */
    private boolean isAssisted(TypeElement bean) {
        for (ExecutableElement ctor : ElementFilter.constructorsIn(bean.getEnclosedElements())) {
            if (AnnotationMirrors.isPresent(ctor, ASSISTED_INJECT_FQN)) {
                return true;
            }
            for (VariableElement param : ctor.getParameters()) {
                if (AnnotationMirrors.isPresent(param, ASSISTED_FQN)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Reports whether any intercepted method on the bean returns a raw (unparameterized) or
     * wildcard-parameterized {@code io.vertx.core.Future}, emitting a {@code Diagnostics.error} on each
     * offending method. Returns {@code true} if at least one such method was found, so the caller skips
     * emitting a proxy for the bean. Both shapes are rejected rather than mis-handled: a raw
     * {@code Future} would be classified as a synchronous return (mis-metering the around-chain), and a
     * wildcard {@code Future<? ...>} would render an invalid back-cast in the generated override.
     *
     * @param bean    the candidate aspect bean (named in the diagnostic)
     * @param methods the bean's intercepted methods
     * @return {@code true} when at least one method returns a raw or wildcard {@code Future}
     */
    private boolean hasUnsupportedFutureReturn(TypeElement bean, List<ExecutableElement> methods) {
        boolean rejected = false;
        for (ExecutableElement method : methods) {
            TypeMirror returnType = method.getReturnType();
            if (!(returnType instanceof DeclaredType declared)) {
                continue;
            }
            if (!ctx.types().erasure(declared).toString().equals(FUTURE_FQN)) {
                continue;
            }
            List<? extends TypeMirror> args = declared.getTypeArguments();
            boolean raw = args.isEmpty();
            boolean wildcard = !raw && args.get(0) instanceof WildcardType;
            if (raw || wildcard) {
                String methodFqn = bean.getQualifiedName() + "." + method.getSimpleName();
                ctx.diagnostics().error(method, Diagnostics.aopUnsupportedFutureReturn(methodFqn, wildcard));
                rejected = true;
            }
        }
        return rejected;
    }

    /**
     * Reports whether the bean (or any of its intercepted methods) has a shape the subclass-proxy
     * strategy cannot proxy, emitting a {@code Diagnostics.error} on each offending element. Returns
     * {@code true} if at least one such shape was found, so the caller skips emitting a proxy for the
     * bean. All offending shapes are reported (the method does not short-circuit) so a single build
     * surfaces every problem.
     *
     * <p>The non-proxyable shapes are:
     * <ul>
     *   <li>a non-{@code public} class — the generated {@code GeneratedAopModule} may live in a
     *       different package (option override or LCP relocation); its {@code @Binds} method
     *       references the bean type by name, making it inaccessible from the module's package if
     *       the class is not {@code public};
     *   <li>a {@code final} class — cannot be subclassed by {@code {Bean}$AopProxy extends Bean};
     *   <li>a {@code final} intercepted method — cannot be {@code @Override}n by the proxy subclass;
     *   <li>a {@code private} intercepted method — not visible to a subclass, so not overrideable;
     *   <li>a {@code static} intercepted method — not an instance method, so not overrideable;
     *   <li>an intercepted method whose {@code throws} clause names a method type variable — the
     *       generated sync guard would emit a non-reifiable {@code instanceof E} (R3-1).
     * </ul>
     *
     * @param bean    the candidate aspect bean (named in the class-level diagnostic)
     * @param methods the bean's intercepted methods
     * @return {@code true} when at least one non-proxyable shape was found
     */
    private boolean isNotProxyable(TypeElement bean, List<ExecutableElement> methods) {
        boolean rejected = false;

        // Per-bean: a non-public class is not accessible from the generated module's package, which
        // may be in a different package (option override or LCP relocation). The generated @Binds
        // references the bean type by name, so a package-private (or protected/private nested) bean
        // is inaccessible from the module, yielding uncompilable generated source. Reject up front
        // with a clear diagnostic (consistent with the @Observes observer accessibility rule).
        if (!bean.getModifiers().contains(Modifier.PUBLIC)) {
            ctx.diagnostics()
                    .error(
                            bean,
                            Diagnostics.aopBeanMustBePublic(
                                    bean.getQualifiedName().toString()));
            rejected = true;
        }

        // Per-bean: a final class cannot be subclassed.
        if (bean.getModifiers().contains(Modifier.FINAL)) {
            ctx.diagnostics()
                    .error(
                            bean,
                            Diagnostics.aopNotProxyable(
                                    bean.getQualifiedName().toString(), "the class is final and cannot be subclassed"));
            rejected = true;
        }

        // Per-intercepted-method: final / private / static / type-variable throws.
        for (ExecutableElement method : methods) {
            String methodFqn = bean.getQualifiedName() + "." + method.getSimpleName();
            Set<Modifier> modifiers = method.getModifiers();
            if (modifiers.contains(Modifier.FINAL)) {
                ctx.diagnostics()
                        .error(
                                method,
                                Diagnostics.aopNotProxyable(
                                        methodFqn, "it is a final method and cannot be overridden"));
                rejected = true;
            }
            if (modifiers.contains(Modifier.PRIVATE)) {
                ctx.diagnostics()
                        .error(
                                method,
                                Diagnostics.aopNotProxyable(methodFqn, "it is private and cannot be overridden"));
                rejected = true;
            }
            if (modifiers.contains(Modifier.STATIC)) {
                ctx.diagnostics()
                        .error(
                                method,
                                Diagnostics.aopNotProxyable(
                                        methodFqn, "it is a static method and cannot be overridden"));
                rejected = true;
            }
            if (hasTypeVariableThrows(method)) {
                ctx.diagnostics()
                        .error(
                                method,
                                Diagnostics.aopNotProxyable(
                                        methodFqn,
                                        "it declares a type variable in its throws clause, which the sync guard"
                                                + " cannot reify (instanceof on a type variable)"));
                rejected = true;
            }
        }
        return rejected;
    }

    /**
     * Reports whether any aspect <em>trigger</em> annotation used on the bean's intercepted methods
     * declares a member of an unsupported attribute kind ({@code char} / {@code float} / {@code double},
     * a nested-annotation member, or an array of those), emitting a {@code Diagnostics.error} on each
     * offending occurrence. Returns {@code true} if at least one such aspect was found, so the caller
     * skips emitting a proxy for the bean.
     *
     * <p>The proxy bakes a per-aspect {@code <Ann>$AopLiteral} class and a per-occurrence literal instance
     * for every aspect trigger on the bean. An unsupported member kind makes
     * {@link dev.vertique.codegen.meta.AnnotationLiteralEmitter#emit} /
     * {@link dev.vertique.codegen.meta.AnnotationLiteralEmitter#constructorArgs} throw
     * {@code UnsupportedOperationException} mid-emission — a processor crash with a stack trace. This
     * up-front precheck routes the same condition through {@code Diagnostics.error} for a clean compile
     * error (FR-013-13 / FR-013-09c, P2-W2), mirroring the method-annotation precheck in
     * {@code AopProxyEmitter.materializeMethodAnnotations}.
     *
     * <p>The diagnostic is emitted on the offending <em>method occurrence</em> so the error points at
     * the use site; the same aspect type appearing on multiple methods is reported per occurrence (the
     * method does not short-circuit) so a single build surfaces every problem.
     *
     * @param methods    the bean's intercepted methods
     * @param aspectFqns the FQNs of the aspect trigger annotations the processor recognises
     * @return {@code true} when at least one aspect trigger has an unsupported attribute kind
     */
    private boolean hasUnsupportedAspectAttribute(List<ExecutableElement> methods, Set<String> aspectFqns) {
        boolean rejected = false;
        for (ExecutableElement method : methods) {
            for (AnnotationMirror mirror : method.getAnnotationMirrors()) {
                TypeElement annType = (TypeElement) mirror.getAnnotationType().asElement();
                if (!aspectFqns.contains(annType.getQualifiedName().toString())) {
                    continue;
                }
                Optional<AnnotationLiteralEmitter.UnsupportedAttribute> unsupported =
                        AnnotationLiteralEmitter.firstUnsupportedAttribute(annType);
                if (unsupported.isPresent()) {
                    AnnotationLiteralEmitter.UnsupportedAttribute bad = unsupported.get();
                    ctx.diagnostics()
                            .error(
                                    method,
                                    Diagnostics.unsupportedAnnotationAttributeKind(
                                            annType.getQualifiedName().toString(), bad.member(), bad.kind()));
                    rejected = true;
                }
            }
        }
        return rejected;
    }

    /**
     * Returns {@code true} when any of the method's declared {@code throws} types is a type variable
     * (e.g. {@code <E extends IOException> … throws E}). Such a throw makes the generated sync guard
     * emit a non-reifiable {@code instanceof E} (R3-1), so the method cannot be proxied.
     *
     * @param method the intercepted method to inspect
     * @return {@code true} if the method declares a type-variable {@code throws}
     */
    private boolean hasTypeVariableThrows(ExecutableElement method) {
        for (TypeMirror thrown : method.getThrownTypes()) {
            if (thrown.getKind() == TypeKind.TYPEVAR) {
                return true;
            }
        }
        return false;
    }

    /**
     * Scans the round's root elements for user {@code @Provides} methods and returns the set of
     * fully-qualified return-type names they supply. A bean appearing here is already user-bound, so
     * the processor must not also emit a binding for it.
     *
     * @param roundEnv the current processing round
     * @return the FQNs of types returned by a user {@code @Provides} method this round
     */
    private Set<String> collectUserProvidedTypes(RoundEnvironment roundEnv) {
        Set<String> provided = new LinkedHashSet<>();
        for (Element root : roundEnv.getRootElements()) {
            if (!(root instanceof TypeElement type)) {
                continue;
            }
            for (ExecutableElement method : ElementFilter.methodsIn(type.getEnclosedElements())) {
                if (AnnotationMirrors.isPresent(method, PROVIDES_FQN)) {
                    TypeMirror returnType = method.getReturnType();
                    ctx.asTypeElement(returnType)
                            .ifPresent(te -> provided.add(te.getQualifiedName().toString()));
                }
            }
        }
        return provided;
    }

    // --- discovery helpers ---

    /** Returns {@code true} if the annotation type is itself meta-annotated with {@link Aspect}. */
    private boolean isAspectTrigger(TypeElement annotation) {
        return AnnotationMirrors.isPresent(annotation, ASPECT_FQN);
    }

    /** Groups the methods carrying any aspect trigger by their declaring bean, in stable order. */
    private Map<TypeElement, List<ExecutableElement>> collectBeans(
            Set<TypeElement> triggers, RoundEnvironment roundEnv) {
        Map<TypeElement, List<ExecutableElement>> beans = new LinkedHashMap<>();
        // De-duplicate methods that carry more than one trigger.
        Set<ExecutableElement> seen = new LinkedHashSet<>();
        for (TypeElement trigger : triggers) {
            for (Element annotated : roundEnv.getElementsAnnotatedWith(trigger)) {
                if (annotated.getKind() != ElementKind.METHOD) {
                    continue;
                }
                ExecutableElement method = (ExecutableElement) annotated;
                if (!seen.add(method)) {
                    continue;
                }
                Element enclosing = method.getEnclosingElement();
                if (enclosing instanceof TypeElement bean) {
                    beans.computeIfAbsent(bean, k -> new ArrayList<>()).add(method);
                }
            }
        }
        // Re-sort each bean's methods into declaration order for deterministic output.
        for (Map.Entry<TypeElement, List<ExecutableElement>> e : beans.entrySet()) {
            List<? extends Element> declared = e.getKey().getEnclosedElements();
            e.getValue().sort((a, b) -> Integer.compare(declared.indexOf(a), declared.indexOf(b)));
        }
        return beans;
    }

    /**
     * Resolves the bean's declared scope annotation (one meta-annotated with {@code @Scope}), or
     * {@code null} when the bean is unscoped. Never defaults to {@code @Singleton}.
     */
    private ClassName scopeOf(TypeElement bean) {
        for (AnnotationMirror mirror : bean.getAnnotationMirrors()) {
            Element annElement = mirror.getAnnotationType().asElement();
            if (annElement instanceof TypeElement annType && isScope(annType)) {
                return ClassName.get(annType);
            }
        }
        return null;
    }

    /** Returns {@code true} when the annotation type is meta-annotated with {@code @Scope}. */
    private boolean isScope(TypeElement annotationType) {
        return annotationType.getAnnotationMirrors().stream().anyMatch(m -> {
            Element e = m.getAnnotationType().asElement();
            if (e instanceof TypeElement te) {
                String fqn = te.getQualifiedName().toString();
                return fqn.equals(SCOPE_FQN) || fqn.equals(JAVAX_SCOPE_FQN);
            }
            return false;
        });
    }
}
