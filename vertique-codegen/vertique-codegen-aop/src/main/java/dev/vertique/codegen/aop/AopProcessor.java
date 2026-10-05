// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.aop;

import com.palantir.javapoet.ClassName;
import dev.vertique.aop.Aspect;
import dev.vertique.codegen.AnnotationMirrors;
import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.Diagnostics;
import dev.vertique.codegen.validate.InjectConstructorValidator;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
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
import javax.lang.model.type.ExecutableType;
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

        // Aspect trigger FQNs from this round's annotation types plus inherited/interface methods on
        // classpath types that getElementsAnnotatedWith does not re-report.
        Set<String> aspectFqns = discoverAspectTriggerFqns(annotations, roundEnv);

        if (!aspectFqns.isEmpty()) {
            // Collect the FQNs of bean types supplied by a user @Provides in this compilation unit.
            Set<String> userProvidedTypes = collectUserProvidedTypes(roundEnv);

            // Group aspect-annotated methods by their declaring bean.
            Map<TypeElement, List<ExecutableElement>> beans = collectBeans(aspectFqns, roundEnv);
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
            TypeMirror returnType = asMemberExecutableType(bean, method).getReturnType();
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

    /**
     * Groups the methods carrying any aspect trigger by their declaring bean, in stable order.
     *
     * <p>Class-declared triggers are attributed to that class. Interface-declared triggers are
     * woven onto every concrete class in the round that implements the interface and inherits or
     * overrides the method (including inherited {@code default} methods that the class does not
     * redeclare). A hierarchy scan of each class root also picks up aspect triggers retained on
     * classpath interfaces that {@link RoundEnvironment#getElementsAnnotatedWith} does not
     * re-report.
     */
    private Set<String> discoverAspectTriggerFqns(
            Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        Set<String> triggerFqns = new LinkedHashSet<>();
        for (TypeElement annotation : annotations) {
            if (annotation.getKind() == ElementKind.ANNOTATION_TYPE && isAspectTrigger(annotation)) {
                triggerFqns.add(annotation.getQualifiedName().toString());
            }
        }
        for (Element root : roundEnv.getRootElements()) {
            collectAspectTriggerFqnsFromType(root, triggerFqns);
        }
        return triggerFqns;
    }

    private void collectAspectTriggerFqnsFromType(Element element, Set<String> triggerFqns) {
        if (!(element instanceof TypeElement type)) {
            return;
        }
        if (type.getKind() == ElementKind.CLASS) {
            for (TypeElement iface : allInterfaces(type)) {
                for (ExecutableElement method : ElementFilter.methodsIn(iface.getEnclosedElements())) {
                    collectAspectTriggerFqnsFromElement(method, triggerFqns);
                }
            }
        }
        for (ExecutableElement method : ElementFilter.methodsIn(type.getEnclosedElements())) {
            collectAspectTriggerFqnsFromElement(method, triggerFqns);
        }
        for (Element enclosed : type.getEnclosedElements()) {
            if (enclosed.getKind() == ElementKind.CLASS) {
                collectAspectTriggerFqnsFromType(enclosed, triggerFqns);
            }
        }
    }

    private void collectAspectTriggerFqnsFromElement(Element element, Set<String> triggerFqns) {
        for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
            Element annElement = mirror.getAnnotationType().asElement();
            if (annElement instanceof TypeElement annType && isAspectTrigger(annType)) {
                triggerFqns.add(annType.getQualifiedName().toString());
            }
            for (var entry : mirror.getElementValues().entrySet()) {
                if (!(entry.getValue().getValue() instanceof List<?> values)) {
                    continue;
                }
                for (Object value : values) {
                    if (value instanceof javax.lang.model.element.AnnotationValue annotationValue
                            && annotationValue.getValue() instanceof AnnotationMirror nested) {
                        Element nestedElement = nested.getAnnotationType().asElement();
                        if (nestedElement instanceof TypeElement nestedType && isAspectTrigger(nestedType)) {
                            triggerFqns.add(nestedType.getQualifiedName().toString());
                        }
                    }
                }
            }
        }
    }

    private Map<TypeElement, List<ExecutableElement>> collectBeans(
            Set<String> triggerFqns, RoundEnvironment roundEnv) {
        Map<TypeElement, List<ExecutableElement>> beans = new LinkedHashMap<>();
        // De-duplicate annotated elements that carry more than one trigger type.
        Set<ExecutableElement> seenAnnotated = new LinkedHashSet<>();
        for (String triggerFqn : triggerFqns) {
            TypeElement trigger = ctx.elements().getTypeElement(triggerFqn);
            if (trigger == null) {
                continue;
            }
            for (Element annotated : roundEnv.getElementsAnnotatedWith(trigger)) {
                if (annotated.getKind() != ElementKind.METHOD) {
                    continue;
                }
                ExecutableElement method = (ExecutableElement) annotated;
                if (!seenAnnotated.add(method)) {
                    continue;
                }
                Element enclosing = method.getEnclosingElement();
                if (enclosing.getKind() == ElementKind.CLASS && enclosing instanceof TypeElement bean) {
                    addInterceptedMethod(beans, bean, methodForWeaving(bean, method));
                } else if (enclosing.getKind() == ElementKind.INTERFACE && enclosing instanceof TypeElement iface) {
                    for (TypeElement impl : implementingClasses(iface, roundEnv)) {
                        if (isConcreteMember(impl, method)) {
                            addInterceptedMethod(beans, impl, methodForWeaving(impl, method));
                        }
                    }
                }
            }
        }
        // Classpath interface methods are not re-reported by getElementsAnnotatedWith; walk each
        // class root's interface hierarchy so inherited triggers still weave onto the implementor.
        for (Element root : roundEnv.getRootElements()) {
            collectInterfaceTriggersFromType(root, triggerFqns, beans);
        }
        // Stable order: class-declared methods by declaration index, then inherited by signature.
        for (Map.Entry<TypeElement, List<ExecutableElement>> e : beans.entrySet()) {
            List<? extends Element> declared = e.getKey().getEnclosedElements();
            e.getValue().sort((a, b) -> {
                int ia = declared.indexOf(a);
                int ib = declared.indexOf(b);
                if (ia >= 0 || ib >= 0) {
                    if (ia < 0) {
                        ia = Integer.MAX_VALUE;
                    }
                    if (ib < 0) {
                        ib = Integer.MAX_VALUE;
                    }
                    int byIndex = Integer.compare(ia, ib);
                    if (byIndex != 0) {
                        return byIndex;
                    }
                }
                int byName =
                        a.getSimpleName().toString().compareTo(b.getSimpleName().toString());
                if (byName != 0) {
                    return byName;
                }
                return methodSignatureKey(a).compareTo(methodSignatureKey(b));
            });
        }
        return beans;
    }

    /**
     * Adds {@code method} to {@code bean}'s intercepted list, de-duplicating by erased signature.
     * When both a class-declared method and an interface method contribute the same signature, the
     * class method wins so {@code super.<method>(...)} targets the override.
     */
    private void addInterceptedMethod(
            Map<TypeElement, List<ExecutableElement>> beans, TypeElement bean, ExecutableElement method) {
        List<ExecutableElement> methods = beans.computeIfAbsent(bean, k -> new ArrayList<>());
        String key = methodSignatureKey(method);
        for (int i = 0; i < methods.size(); i++) {
            if (!methodSignatureKey(methods.get(i)).equals(key)) {
                continue;
            }
            ExecutableElement existing = methods.get(i);
            boolean existingOnClass = existing.getEnclosingElement().getKind() == ElementKind.CLASS;
            boolean incomingOnClass = method.getEnclosingElement().getKind() == ElementKind.CLASS;
            if (incomingOnClass && !existingOnClass) {
                methods.set(i, method);
            }
            return;
        }
        methods.add(method);
    }

    /** Erased {@code name(paramTypes)} key used to de-duplicate inherited vs declared methods. */
    private String methodSignatureKey(ExecutableElement method) {
        StringBuilder sb = new StringBuilder(method.getSimpleName());
        sb.append('(');
        for (VariableElement param : method.getParameters()) {
            sb.append(ctx.types().erasure(param.asType())).append(',');
        }
        sb.append(')');
        return sb.toString();
    }

    /** Returns {@code true} when {@code method} carries any trigger in {@code triggerFqns}. */
    private boolean hasAspectTrigger(ExecutableElement method, Set<String> triggerFqns) {
        for (AnnotationMirror mirror : method.getAnnotationMirrors()) {
            Element annElement = mirror.getAnnotationType().asElement();
            if (annElement instanceof TypeElement annType
                    && triggerFqns.contains(annType.getQualifiedName().toString())) {
                return true;
            }
            // Expand repeatable containers so each nested aspect occurrence is discovered.
            for (var entry : mirror.getElementValues().entrySet()) {
                if (!(entry.getValue().getValue() instanceof List<?> values)) {
                    continue;
                }
                for (Object value : values) {
                    if (value instanceof javax.lang.model.element.AnnotationValue annotationValue
                            && annotationValue.getValue() instanceof AnnotationMirror nested) {
                        Element nestedElement = nested.getAnnotationType().asElement();
                        if (nestedElement instanceof TypeElement nestedType
                                && triggerFqns.contains(
                                        nestedType.getQualifiedName().toString())) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private void collectInterfaceTriggersFromType(
            Element element, Set<String> triggerFqns, Map<TypeElement, List<ExecutableElement>> beans) {
        if (!(element instanceof TypeElement type) || type.getKind() != ElementKind.CLASS) {
            return;
        }
        for (TypeElement iface : allInterfaces(type)) {
            for (ExecutableElement method : ElementFilter.methodsIn(iface.getEnclosedElements())) {
                if (hasAspectTrigger(method, triggerFqns) && isConcreteMember(type, method)) {
                    addInterceptedMethod(beans, type, methodForWeaving(type, method));
                }
            }
        }
        for (Element enclosed : type.getEnclosedElements()) {
            if (enclosed.getKind() == ElementKind.CLASS) {
                collectInterfaceTriggersFromType(enclosed, triggerFqns, beans);
            }
        }
    }

    /**
     * Returns every concrete {@code class} in the round (including nested) that is assignable to
     * {@code iface}.
     */
    private List<TypeElement> implementingClasses(TypeElement iface, RoundEnvironment roundEnv) {
        List<TypeElement> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        TypeMirror ifaceType = ctx.types().erasure(iface.asType());
        for (Element root : roundEnv.getRootElements()) {
            collectImplementingClasses(root, ifaceType, result, seen);
        }
        return result;
    }

    private void collectImplementingClasses(
            Element element, TypeMirror ifaceType, List<TypeElement> result, Set<String> seen) {
        if (element instanceof TypeElement type && type.getKind() == ElementKind.CLASS) {
            if (ctx.types().isAssignable(ctx.types().erasure(type.asType()), ifaceType)) {
                String fqn = type.getQualifiedName().toString();
                if (seen.add(fqn)) {
                    result.add(type);
                }
            }
            for (Element enclosed : type.getEnclosedElements()) {
                if (enclosed.getKind() == ElementKind.CLASS) {
                    collectImplementingClasses(enclosed, ifaceType, result, seen);
                }
            }
        }
    }

    /**
     * Prefers the bean's own override when present; otherwise keeps the interface declaration for
     * inherited {@code default} methods.
     */
    private ExecutableElement methodForWeaving(TypeElement bean, ExecutableElement method) {
        for (ExecutableElement candidate :
                ElementFilter.methodsIn(ctx.elements().getAllMembers(bean))) {
            if (ctx.elements().overrides(candidate, method, bean)) {
                return candidate;
            }
        }
        return method;
    }

    private ExecutableType asMemberExecutableType(TypeElement bean, ExecutableElement method) {
        return (ExecutableType) ctx.types().asMemberOf((DeclaredType) bean.asType(), method);
    }

    /**
     * Returns {@code true} when {@code bean} inherits or overrides {@code ifaceMethod} with a
     * concrete (non-abstract) member — a class body or an inherited interface {@code default}.
     */
    private boolean isConcreteMember(TypeElement bean, ExecutableElement ifaceMethod) {
        for (ExecutableElement candidate :
                ElementFilter.methodsIn(ctx.elements().getAllMembers(bean))) {
            if (candidate.equals(ifaceMethod) || ctx.elements().overrides(candidate, ifaceMethod, bean)) {
                return !candidate.getModifiers().contains(Modifier.ABSTRACT);
            }
        }
        return false;
    }

    /**
     * Returns all transitively implemented interfaces of {@code typeElement} in BFS discovery order.
     */
    private List<TypeElement> allInterfaces(TypeElement typeElement) {
        List<TypeElement> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Deque<TypeElement> queue = new ArrayDeque<>();
        TypeElement current = typeElement;
        while (current != null
                && !"java.lang.Object".equals(current.getQualifiedName().toString())) {
            enqueueDirectInterfaces(current, queue, seen);
            current = ctx.asTypeElement(current.getSuperclass()).orElse(null);
        }
        while (!queue.isEmpty()) {
            TypeElement iface = queue.poll();
            result.add(iface);
            enqueueDirectInterfaces(iface, queue, seen);
        }
        return result;
    }

    private void enqueueDirectInterfaces(TypeElement type, Deque<TypeElement> queue, Set<String> seen) {
        for (TypeMirror mirror : type.getInterfaces()) {
            ctx.asTypeElement(mirror).ifPresent(iface -> {
                if (seen.add(iface.getQualifiedName().toString())) {
                    queue.add(iface);
                }
            });
        }
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
