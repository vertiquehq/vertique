// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.events;

import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeSpec;
import dev.vertique.codegen.AnnotationMirrors;
import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.Diagnostics;
import dev.vertique.codegen.PackageResolver;
import dev.vertique.codegen.dagger.DaggerModuleWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedOptions;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;

/**
 * Annotation processor that builds the event-type inventory and generates an injectable
 * {@code X$Event extends Event<X>} publisher, a {@code GeneratedEventsModule} {@code @Binds}
 * {@code Event<X>} for every inventory type, and a {@code @Provides @IntoSet ObserverRegistration}
 * for every {@code @Observes} observer method.
 *
 * <p>The inventory is the union of:
 * <ul>
 *   <li>the distinct {@code @Observes T} parameter types declared by observer methods, and
 *   <li>the distinct {@code Event<T>} type arguments injected at <em>constructor</em> {@code @Inject}
 *       parameters, unwrapping the standard Dagger wrappers {@code Provider<Event<T>>} and
 *       {@code Lazy<Event<T>>}.
 * </ul>
 *
 * <p>Field injection, method injection, and {@code @Component} provision methods are <strong>not</strong>
 * scanned in v1 (the framework's constructor-injection convention). For each inventory type {@code X}
 * the processor emits {@code final class X$Event extends Event<X>} in the <em>event type's own
 * package</em> (not a shared LCP package) with an {@code @Inject} constructor
 * {@code X$Event(ObserverRegistry r) { super(r, X.class); }} and a {@code @Binds Event<X>} in a single
 * {@code @Generated GeneratedEventsModule}. An <em>unobserved</em> fired type still gets a valid no-op
 * publisher. Observer methods must return {@code void}; a non-{@code void} return is a compile error.
 *
 * <p>Observer methods must also declare exactly one parameter and that parameter must carry
 * {@code @Observes}; multi-parameter observers are rejected with a compile error (P3-W2).
 * Observing {@code java.lang.Object} directly is also rejected (P3-W5) because the registry's
 * superclass walk excludes {@code Object.class}, so such an observer would silently never fire.
 *
 * <p>Observer methods must be {@code public} (W2): the generated lambda
 * {@code e -> bean.method((EventType) e)} lives in {@code GeneratedEventsModule}. Private,
 * protected, and package-private methods are unreachable from there and cause a generated-source
 * compile error; they are rejected at codegen time with a
 * {@link dev.vertique.codegen.Diagnostics#observerMustBeAccessible} diagnostic.
 *
 * <p>Event payload types — both from {@code @Observes} parameters and {@code Event<T>} constructor
 * injections — must not be parameterized (generic) (W3). A parameterized payload such as
 * {@code Box<String>} would be erased to {@code Box} and produce a {@code @Binds Event<Box>}
 * binding that does not satisfy Dagger's {@code Event<Box<String>>} key, causing a
 * {@code MissingBinding} error at the app component. Such payloads are rejected with a
 * {@link dev.vertique.codegen.Diagnostics#eventPayloadMustNotBeParameterized} diagnostic.
 *
 * <p>The generated {@code GeneratedEventsModule} always declares a {@code @Multibinds abstract
 * Set<ObserverRegistration> observerRegistrations()} so that {@link dev.vertique.events.ObserverRegistry}'s
 * {@code @Inject} constructor can be satisfied even when there are no observers in the compilation
 * (the publisher-only case). Each observer method also gets a static
 * {@code @Provides @IntoSet ObserverRegistration} method whose body constructs the registration with
 * the event-type class literal, the observer's priority, and a reflection-free lambda that casts
 * and delegates to the bean method.
 *
 * <p>This class is registered via {@code META-INF/services/javax.annotation.processing.Processor}.
 */
@SupportedAnnotationTypes("*")
@SupportedSourceVersion(SourceVersion.RELEASE_21)
@SupportedOptions(CodegenContext.OPTION_OUTPUT_PACKAGE)
public final class EventsProcessor extends AbstractProcessor {

    // --- FQN constants ---

    private static final String OBSERVES_FQN = "dev.vertique.events.Observes";
    private static final String EVENT_FQN = "dev.vertique.events.Event";
    private static final String INJECT_JAKARTA_FQN = "jakarta.inject.Inject";
    private static final String INJECT_JAVAX_FQN = "javax.inject.Inject";
    private static final String PROVIDER_JAKARTA_FQN = "jakarta.inject.Provider";
    private static final String LAZY_DAGGER_FQN = "dagger.Lazy";
    private static final String PROCESSOR_FQN = "dev.vertique.codegen.events.EventsProcessor";

    // --- ClassName constants for emitted classes ---

    private static final ClassName INJECT = ClassName.get("jakarta.inject", "Inject");
    private static final ClassName EVENT = ClassName.get("dev.vertique.events", "Event");
    private static final ClassName OBSERVER_REGISTRY = ClassName.get("dev.vertique.events", "ObserverRegistry");
    private static final ClassName OBSERVER_REGISTRATION = ClassName.get("dev.vertique.events", "ObserverRegistration");

    private static final String MODULE_SIMPLE_NAME = "GeneratedEventsModule";
    private static final String FALLBACK_PACKAGE = "vertique.generated.events";
    private static final String PUBLISHER_SUFFIX = "$Event";
    private static final int DEFAULT_PRIORITY = 1000;

    // --- Inner record ---

    /**
     * Captures the metadata for a single {@code @Observes} observer method discovered during the scan.
     *
     * <p>Each instance represents one observer: the bean class that declares the method, the method
     * element itself, the declared priority from the {@link dev.vertique.events.Observes} annotation,
     * and the event type the method observes.
     *
     * @param beanType     the enclosing class that declares the observer method
     * @param method       the observer method element (the method whose parameter carries {@code @Observes})
     * @param priority     the dispatch priority declared on {@code @Observes} (default {@value EventsProcessor#DEFAULT_PRIORITY})
     * @param eventType    the event type this observer observes (the type of the {@code @Observes} parameter)
     */
    private record ObserverInfo(TypeElement beanType, ExecutableElement method, int priority, TypeElement eventType) {}

    private CodegenContext ctx;

    /**
     * Creates the events processor.
     */
    public EventsProcessor() {
        // No-arg constructor required by the ServiceLoader registration in META-INF/services.
    }

    /**
     * Initializes the processor from the processing environment.
     *
     * @param env the annotation processing environment for this compilation
     */
    @Override
    public synchronized void init(ProcessingEnvironment env) {
        super.init(env);
        this.ctx = new CodegenContext(env);
    }

    /**
     * Scans the compilation for {@code @Observes} parameter types and constructor-injected
     * {@code Event<T>} type arguments, validates observer-method return types, emits one
     * {@code X$Event} publisher per inventory type, and emits a single {@code GeneratedEventsModule}
     * with a parameterized {@code @Binds Event<X>} per type.
     *
     * @param annotations the annotation types requested to be processed this round
     * @param roundEnv    the environment for the current processing round
     * @return {@code false} — the processor never claims the round, so sibling processors still
     *     observe the same annotations
     */
    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver()) {
            return false;
        }

        // --- Step 1: Collect inventory via observer scan + @Inject constructor scan ---

        // Map from event-type FQN to TypeElement (dedup by FQN, stable insertion order)
        Map<String, TypeElement> inventory = new LinkedHashMap<>();
        // Track event-type origins for package resolution
        Set<Element> originElements = new LinkedHashSet<>();
        // Ordered list of all discovered observer methods (one ObserverInfo per @Observes parameter)
        List<ObserverInfo> observers = new ArrayList<>();

        boolean valid = true;

        // 1a. @Observes scan: every method with at least one @Observes-annotated parameter is
        //     scanned.  Three validations run before harvesting:
        //     (a) void-return rule,
        //     (b) exactly-one-parameter rule (P3-W2), and
        //     (c) @Observes Object rejection (P3-W5).
        for (Element root : roundEnv.getRootElements()) {
            if (!(root instanceof TypeElement type)) {
                continue;
            }
            for (ExecutableElement method : ElementFilter.methodsIn(type.getEnclosedElements())) {
                // Collect parameters annotated with @Observes (may be 0 for non-observer methods).
                List<? extends VariableElement> observesParams = method.getParameters().stream()
                        .filter(p -> AnnotationMirrors.isPresent(p, OBSERVES_FQN))
                        .toList();
                if (observesParams.isEmpty()) {
                    continue;
                }

                String methodFqn = type.getQualifiedName() + "." + method.getSimpleName();

                // (a) Void-return validation.
                if (method.getReturnType().getKind() != TypeKind.VOID) {
                    ctx.diagnostics().error(method, Diagnostics.observerMustReturnVoid(methodFqn));
                    valid = false;
                }

                // (b) Exactly-one-parameter validation (P3-W2): the method must have exactly one
                //     parameter total, and that parameter must be the @Observes one.
                if (method.getParameters().size() != 1) {
                    ctx.diagnostics().error(method, Diagnostics.observerMustHaveSingleParameter(methodFqn));
                    valid = false;
                    continue; // Cannot harvest from a multi-param method — skip.
                }

                // The single parameter is the @Observes one.
                VariableElement observesParam = observesParams.get(0);
                TypeMirror paramType = observesParam.asType();

                // (c) @Observes Object rejection (P3-W5): Object.class is excluded from the
                //     registry's superclass walk, so such an observer would silently never fire.
                if (isJavaLangObject(paramType)) {
                    ctx.diagnostics().error(method, Diagnostics.observerMustNotObserveObject(methodFqn));
                    valid = false;
                    continue; // Skip inventory + observer registration.
                }

                // (d) Accessibility check (W2): the observer method must be public so the generated
                //     lambda (e -> bean.method((EventType) e)) in GeneratedEventsModule can call it.
                //     Private methods cannot be called from outside the class. Protected and
                //     package-private methods fail when the module is in a different package from the
                //     bean (which is not guaranteed — the module uses the LCP of all event packages).
                if (!method.getModifiers().contains(Modifier.PUBLIC)) {
                    ctx.diagnostics().error(method, Diagnostics.observerMustBeAccessible(methodFqn));
                    valid = false;
                    continue; // Skip inventory + observer registration.
                }

                // (e) Parameterized payload rejection (W3): the @Observes parameter type must not
                //     be a parameterized (generic) type such as Box<String>. The v1 inventory stores
                //     event types as erased TypeElements; a parameterized payload would be erased to
                //     Box and emit a @Binds Event<Box> that does not satisfy Dagger's
                //     Event<Box<String>> key, causing a MissingBinding error in the app component.
                if (isParameterizedDeclaredType(paramType)) {
                    ctx.diagnostics()
                            .error(method, Diagnostics.eventPayloadMustNotBeParameterized(paramType.toString()));
                    valid = false;
                    continue; // Skip inventory + observer registration.
                }

                // Harvest the observed event type and record the observer.
                ctx.asTypeElement(paramType).ifPresent(te -> {
                    addToInventory(te, inventory, originElements);
                    int priority = readObservesPriority(observesParam);
                    observers.add(new ObserverInfo(type, method, priority, te));
                });
            }
        }

        // 1b. @Inject constructor scan: unwrap Event<T>, Provider<Event<T>>, Lazy<Event<T>>.
        for (Element root : roundEnv.getRootElements()) {
            if (!(root instanceof TypeElement type)) {
                continue;
            }
            for (ExecutableElement ctor : ElementFilter.constructorsIn(type.getEnclosedElements())) {
                if (!isInjectConstructor(ctor)) {
                    continue;
                }
                for (VariableElement param : ctor.getParameters()) {
                    TypeMirror paramType = param.asType();
                    TypeMirror eventArg = unwrapToEventArg(paramType);
                    if (eventArg != null) {
                        // (W3) Reject parameterized (generic) event payload types: e.g.
                        // Event<Box<String>> unwraps to Box<String>, which is parameterized.
                        // Recording it as erased Box would emit Event<Box> — a binding that
                        // does not satisfy Dagger's Event<Box<String>> key.
                        if (isParameterizedDeclaredType(eventArg)) {
                            ctx.diagnostics()
                                    .error(param, Diagnostics.eventPayloadMustNotBeParameterized(eventArg.toString()));
                            valid = false;
                        } else {
                            ctx.asTypeElement(eventArg).ifPresent(te -> addToInventory(te, inventory, originElements));
                        }
                    }
                }
            }
        }

        // If validation failed (non-void observer), skip emission — the error is already reported.
        if (!valid) {
            return false;
        }

        // Nothing to emit if the inventory is empty.
        if (inventory.isEmpty()) {
            return false;
        }

        // --- Step 2: Resolve output package ---
        String pkg = resolvePackage(originElements);
        if (pkg == null || pkg.isBlank()) {
            pkg = FALLBACK_PACKAGE;
        }

        // --- Step 3: Emit one X$Event publisher per inventory type ---
        // Each publisher is placed in its event type's own package (P3-W3): two distinct-FQN event
        // types sharing the same simple name (e.g. com.acme.OrderCreated, com.shop.OrderCreated)
        // then land in different packages and never produce a name collision.
        // Dedup by the publisher's own FQN — only emit each publisher once per compilation.
        Set<String> emittedPublishers = new LinkedHashSet<>();
        for (Map.Entry<String, TypeElement> entry : inventory.entrySet()) {
            TypeElement eventType = entry.getValue();
            String eventPkg =
                    ctx.elements().getPackageOf(eventType).getQualifiedName().toString();
            String publisherFqn =
                    (eventPkg.isEmpty() ? "" : eventPkg + ".") + eventType.getSimpleName() + PUBLISHER_SUFFIX;
            if (emittedPublishers.add(publisherFqn)) {
                emitPublisher(eventType);
            }
        }

        // --- Step 4: Emit GeneratedEventsModule ---
        emitModule(inventory, observers, pkg);

        return false;
    }

    // --- Inventory helpers ---

    /**
     * Adds the event-type element to the inventory map (keyed by FQN) and records it as an origin
     * element for package resolution. No-ops if the FQN is already present.
     *
     * @param eventType      the event type to add
     * @param inventory      the accumulation map (FQN → TypeElement)
     * @param originElements the set of origin elements for package resolution
     */
    private void addToInventory(
            TypeElement eventType, Map<String, TypeElement> inventory, Set<Element> originElements) {
        String fqn = eventType.getQualifiedName().toString();
        if (inventory.putIfAbsent(fqn, eventType) == null) {
            originElements.add(eventType);
        }
    }

    /**
     * Reads the {@code priority} attribute from the {@link dev.vertique.events.Observes} annotation
     * on the given parameter element.
     *
     * <p>The annotation is {@link java.lang.annotation.RetentionPolicy#SOURCE SOURCE}-retained, so it
     * is available during annotation processing but not at runtime. The attribute is read via the APT
     * mirror API using {@link AnnotationMirrors#attribute} with defaults included. Falls back to
     * {@value #DEFAULT_PRIORITY} when the annotation mirror cannot be found (should not happen
     * since this method is only called after confirming the annotation is present).
     *
     * @param param the method parameter that carries {@code @Observes}
     * @return the declared priority, or {@value #DEFAULT_PRIORITY} if absent
     */
    private int readObservesPriority(VariableElement param) {
        return AnnotationMirrors.findByFqn(param, OBSERVES_FQN)
                .map(mirror -> ctx.annotations()
                        .attribute(mirror, "priority", Integer.class)
                        .orElse(DEFAULT_PRIORITY))
                .orElse(DEFAULT_PRIORITY);
    }

    /**
     * Returns {@code true} when the constructor is annotated with {@code @jakarta.inject.Inject}
     * or {@code @javax.inject.Inject}.
     *
     * @param ctor the constructor to inspect
     * @return {@code true} if this is an {@code @Inject} constructor
     */
    private boolean isInjectConstructor(ExecutableElement ctor) {
        return AnnotationMirrors.isPresent(ctor, INJECT_JAKARTA_FQN)
                || AnnotationMirrors.isPresent(ctor, INJECT_JAVAX_FQN);
    }

    /**
     * Unwraps a constructor parameter type to the {@code T} in {@code Event<T>}, handling these
     * three shapes:
     * <ul>
     *   <li>{@code Event<T>} → returns {@code T}</li>
     *   <li>{@code Provider<Event<T>>} → returns {@code T}</li>
     *   <li>{@code Lazy<Event<T>>} → returns {@code T}</li>
     * </ul>
     *
     * <p>Returns {@code null} for any type that does not match these shapes (non-{@code Event}
     * types, raw {@code Event}, {@code Event<?>}, etc.).
     *
     * @param type the parameter type to inspect
     * @return the inner event type argument {@code T}, or {@code null} when the parameter is not
     *     an {@code Event<T>} (or a Provider/Lazy wrapper of one)
     */
    private TypeMirror unwrapToEventArg(TypeMirror type) {
        if (!(type instanceof DeclaredType declared)) {
            return null;
        }
        String erased = ctx.types().erasure(declared).toString();
        // Direct Event<T>
        if (erased.equals(EVENT_FQN)) {
            return singleTypeArg(declared);
        }
        // Provider<Event<T>> or Lazy<Event<T>>: peel the outer wrapper, then recurse.
        if (erased.equals(PROVIDER_JAKARTA_FQN) || erased.equals(LAZY_DAGGER_FQN)) {
            TypeMirror inner = singleTypeArg(declared);
            if (inner != null) {
                return unwrapToEventArg(inner);
            }
        }
        return null;
    }

    /**
     * Returns the single type argument of a parameterized declared type, or {@code null} when the
     * type is raw (no type arguments) or carries more than one argument.
     *
     * @param declared the parameterized declared type
     * @return the single type argument, or {@code null}
     */
    private TypeMirror singleTypeArg(DeclaredType declared) {
        List<? extends TypeMirror> args = declared.getTypeArguments();
        return args.size() == 1 ? args.get(0) : null;
    }

    /**
     * Returns {@code true} when the given type mirror is exactly {@code java.lang.Object}.
     *
     * <p>Used by the {@code @Observes} scan to reject catch-all {@code @Observes Object} observers
     * (P3-W5): the registry's superclass walk excludes {@code Object.class}, so such an observer
     * would silently never fire.
     *
     * @param type the type to inspect
     * @return {@code true} if the erased type is {@code java.lang.Object}
     */
    private boolean isJavaLangObject(TypeMirror type) {
        return ctx.types().erasure(type).toString().equals("java.lang.Object");
    }

    /**
     * Returns {@code true} when the given type mirror is a parameterized (generic) declared type —
     * i.e. a {@link DeclaredType} with at least one type argument.
     *
     * <p>Used to reject parameterized event payload types (W3): the v1 inventory stores event
     * types as erased {@link TypeElement} references. A parameterized payload such as
     * {@code Box<String>} would be erased to {@code Box} and produce a {@code @Binds Event<Box>}
     * binding that does NOT satisfy Dagger's {@code Event<Box<String>>} key. Rejecting at codegen
     * time yields a clear error instead of a silent wrong binding.
     *
     * <p>Raw types ({@code Box} with no type arguments) are permitted — they have zero type
     * arguments and pass through this check. Only types with one or more concrete or wildcard
     * type arguments are rejected.
     *
     * @param type the type mirror to inspect
     * @return {@code true} if {@code type} is a {@link DeclaredType} with at least one type argument
     */
    private boolean isParameterizedDeclaredType(TypeMirror type) {
        return type instanceof DeclaredType declared
                && !declared.getTypeArguments().isEmpty();
    }

    // --- Package resolution ---

    /**
     * Resolves the output package for the generated types: option override, then LCP of origin
     * elements, then the fallback {@value #FALLBACK_PACKAGE}.
     *
     * @param originElements the event-type elements that seeded the inventory
     * @return the resolved package name; never {@code null}
     */
    private String resolvePackage(Set<Element> originElements) {
        String override = ctx.env().getOptions().get(CodegenContext.OPTION_OUTPUT_PACKAGE);
        if (override != null && !override.isBlank()) {
            return override;
        }
        if (originElements.isEmpty()) {
            return FALLBACK_PACKAGE;
        }
        String lcp = originElements.stream()
                .map(e -> ctx.elements().getPackageOf(e).getQualifiedName().toString())
                .reduce(PackageResolver::longestCommonPrefix)
                .orElse(FALLBACK_PACKAGE);
        return lcp.isBlank() ? FALLBACK_PACKAGE : lcp;
    }

    // --- Emission helpers ---

    /**
     * Emits the {@code X$Event extends Event<X>} publisher class for the given event type.
     *
     * <p>The generated class is placed in the <em>event type's own package</em> (not a shared
     * LCP package). This guarantees that two distinct-FQN event types sharing the same simple
     * name — e.g. {@code com.acme.OrderCreated} and {@code com.shop.OrderCreated} — emit
     * {@code com.acme.OrderCreated$Event} and {@code com.shop.OrderCreated$Event} respectively,
     * with no name collision (P3-W3).
     *
     * <p>The generated class is a {@code final} top-level class whose name literally contains a
     * {@code $} (e.g. {@code OrderCreated$Event}). JavaPoet renders the {@code $} in a
     * {@link ClassName} verbatim because {@code $} is a legal identifier character. The class:
     * <ul>
     *   <li>extends {@code dev.vertique.events.Event<X>};</li>
     *   <li>has an {@code @Inject} constructor that accepts an
     *       {@code dev.vertique.events.ObserverRegistry} and delegates to
     *       {@code super(r, X.class)};
     * </ul>
     *
     * @param eventType the event type {@code X} for which to generate the publisher; the publisher
     *                  is placed in the same package as this type
     */
    private void emitPublisher(TypeElement eventType) {
        String eventSimple = eventType.getSimpleName().toString();
        String publisherSimple = eventSimple + PUBLISHER_SUFFIX;
        ClassName eventTypeName = ClassName.get(eventType);
        String pkg = ctx.elements().getPackageOf(eventType).getQualifiedName().toString();
        ClassName publisherName = ClassName.get(pkg, publisherSimple);

        MethodSpec ctor = MethodSpec.constructorBuilder()
                .addAnnotation(AnnotationSpec.builder(INJECT).build())
                .addParameter(OBSERVER_REGISTRY, "registry")
                .addStatement("super(registry, $T.class)", eventTypeName)
                .build();

        TypeSpec publisherType = TypeSpec.classBuilder(publisherSimple)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .superclass(ParameterizedTypeName.get(EVENT, eventTypeName))
                .addMethod(ctor)
                .build();

        JavaFile file = JavaFile.builder(pkg, publisherType).build();
        try {
            file.writeTo(ctx.filer());
        } catch (IOException e) {
            ctx.diagnostics().error(null, "Failed to write %s: %s", publisherName, e.getMessage());
        }
    }

    /**
     * Emits the single {@code GeneratedEventsModule} Dagger {@code @Module} with:
     * <ol>
     *   <li>one {@code @Binds Event<X>} abstract method per inventory type (publisher binding),</li>
     *   <li>one {@code @Multibinds abstract Set<ObserverRegistration> observerRegistrations()} so
     *       the empty-set case (zero observers) compiles cleanly, and</li>
     *   <li>one static {@code @Provides @IntoSet ObserverRegistration} method per discovered
     *       observer method, with a reflection-free lambda invoker.</li>
     * </ol>
     *
     * <p>The bound publisher type is the parameterized {@code Event<X>} (expressed via
     * {@link ParameterizedTypeName}); the implementation type is the generated {@code X$Event}
     * publisher located in the event type's own package.
     *
     * <p>Each {@code @Binds} method name is {@code bind<SimpleName>_<ordinal>Event} where the
     * ordinal is the zero-based position of the event type in the inventory sorted by FQN in
     * natural {@link String} order (reproducible across compilation runs). This is provably
     * injective: distinct ordinals yield distinct names regardless of how the event types' FQNs
     * relate (same simple name, case differences, underscore/dot-placement variations).
     *
     * <p>Similarly, observer ordinals are assigned after sorting the discovered observer list by
     * (bean-FQN, method-name, event-FQN) — a total order that is independent of the
     * non-deterministic {@link javax.annotation.processing.RoundEnvironment#getRootElements()} set
     * iteration order.
     *
     * <p>The {@code @Multibinds} declaration is emitted unconditionally. It is required even when
     * observers are present because Dagger requires an explicit declaration when the set may be
     * empty across any compilation unit that includes the module.
     *
     * @param inventory the map of FQN → event-type element for all discovered event types
     * @param observers the list of discovered observer methods to emit registrations for
     * @param pkg       the package in which to place the generated module
     */
    private void emitModule(Map<String, TypeElement> inventory, List<ObserverInfo> observers, String pkg) {
        ClassName moduleName = ClassName.get(pkg, MODULE_SIMPLE_NAME);
        DaggerModuleWriter writer = DaggerModuleWriter.named(moduleName).generatedBy(PROCESSOR_FQN);

        // --- Publisher @Binds per event type ---
        // The publisher lives in the event type's own package (P3-W3), so ClassName must use
        // that package rather than the module's package. The bind method name is derived from the
        // event type's simple name and its ORDINAL index in the FQN-sorted inventory
        // (bind<SimpleName>_<ordinal>Event). Sorting by FQN (String natural order) before numbering
        // makes the ordinal assignment reproducible across compilation runs and JVM implementations,
        // regardless of the non-deterministic RoundEnvironment.getRootElements() set iteration
        // order (P3-W7). The ordinal is injective by construction: distinct ordinals always produce
        // distinct method names, regardless of how the FQNs relate (same simple name, case
        // differences, underscore/dot-boundary collisions — P3-W6 history).
        List<Map.Entry<String, TypeElement>> sortedInventory =
                inventory.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList();
        int eventOrdinal = 0;
        for (Map.Entry<String, TypeElement> entry : sortedInventory) {
            TypeElement eventType = entry.getValue();
            ClassName eventTypeName = ClassName.get(eventType);
            String eventPkg =
                    ctx.elements().getPackageOf(eventType).getQualifiedName().toString();
            ClassName publisherName = ClassName.get(eventPkg, eventType.getSimpleName() + PUBLISHER_SUFFIX);

            ParameterizedTypeName boundType = ParameterizedTypeName.get(EVENT, eventTypeName);
            String methodName = "bind" + eventType.getSimpleName() + "_" + eventOrdinal + "Event";
            writer.addBinds(boundType, publisherName, methodName, null);
            eventOrdinal++;
        }

        // --- @Multibinds empty-set anchor (always emitted) ---
        ParameterizedTypeName registrationSet =
                ParameterizedTypeName.get(ClassName.get(Set.class), OBSERVER_REGISTRATION);
        writer.addMultibinds(registrationSet, "observerRegistrations");

        // --- @Provides @IntoSet ObserverRegistration per observer method ---
        // Sort observers by (bean-FQN, method-name, event-FQN) before assigning ordinals so the
        // generated provider method names are reproducible across compilation runs, independent of
        // the non-deterministic RoundEnvironment.getRootElements() iteration order (P3-W7).
        // The ordinal (zero-based index after sorting) guarantees uniqueness even when multiple
        // observer methods in the same bean share the same simple name (P3-W1 overloads).
        List<ObserverInfo> sortedObservers = observers.stream()
                .sorted(Comparator.<ObserverInfo, String>comparing(
                                o -> o.beanType().getQualifiedName().toString())
                        .thenComparing(o -> o.method().getSimpleName().toString())
                        .thenComparing(o -> o.eventType().getQualifiedName().toString()))
                .toList();
        for (int i = 0; i < sortedObservers.size(); i++) {
            emitObserverRegistrationProvider(writer, sortedObservers.get(i), i);
        }

        try {
            writer.build().writeTo(ctx.filer());
        } catch (IOException e) {
            ctx.diagnostics().error(null, "Failed to write %s: %s", MODULE_SIMPLE_NAME, e.getMessage());
        }
    }

    /**
     * Adds a {@code @Provides @IntoSet static ObserverRegistration} method to the module writer for
     * a single observer.
     *
     * <p>The generated method body constructs an {@link dev.vertique.events.ObserverRegistration}
     * with:
     * <ul>
     *   <li>the event-type class literal baked as a constant (reflection-free),</li>
     *   <li>the priority from the {@link dev.vertique.events.Observes} annotation, and</li>
     *   <li>a direct lambda {@code e -> bean.methodName((EventType) e)} — no {@code Method.invoke}.</li>
     * </ul>
     *
     * <p>The bean instance is provided as a Dagger {@code @Provides} method parameter so the
     * injected instance from the graph (respecting scope) is used.
     *
     * <p>The generated method name is derived as
     * {@code provide<BeanSimple><EventSimpleName>_<ordinal>ObserverRegistration} where
     * {@code <EventSimpleName>} is the simple class name of the observed event type (for human
     * readability) and {@code <ordinal>} is the zero-based index of this observer in the overall
     * ordered list. Because the ordinal is globally unique across all observers in the compilation,
     * it alone guarantees uniqueness — no FQN string encoding is needed. The simple name prefix is
     * included only for legibility. This disambiguator covers all cases:
     * <ul>
     *   <li>multiple observer methods in the same bean sharing the same simple name (P3-W1 overloads),
     *       e.g. {@code void on(@Observes EventA e)} and {@code void on(@Observes EventB e)};
     *   <li>two distinct-FQN event types sharing the same simple name (P3-W3), e.g.
     *       {@code com.acme.OrderCreated} and {@code com.shop.OrderCreated};
     *   <li>FQNs differing only in casing (P3-W6) or underscore/dot placement.
     * </ul>
     *
     * @param writer   the module writer to append the provider method to
     * @param observer the observer info capturing bean type, method, priority, and event type
     * @param ordinal  the zero-based index of this observer in the FQN-sorted observer list
     *                 (sorted by bean-FQN, method-name, event-FQN); unique across all observers
     *                 in the compilation and deterministic across compilation runs — the sole
     *                 disambiguator for the generated method name
     */
    private void emitObserverRegistrationProvider(DaggerModuleWriter writer, ObserverInfo observer, int ordinal) {
        ClassName beanTypeName = ClassName.get(observer.beanType());
        ClassName eventTypeName = ClassName.get(observer.eventType());
        String methodSimple = observer.method().getSimpleName().toString();

        // Build a unique provider method name.
        // Pattern: provide<BeanSimple><EventSimpleName>_<ordinal>ObserverRegistration
        // The ordinal is globally unique and deterministic (each observer has a distinct index in
        // the FQN-sorted list — see the sort in emitModule), so it alone guarantees no two
        // providers share a name.  The event simple name is included only for human readability.
        // No FQN string encoding is needed or used.
        String beanSimple = observer.beanType().getSimpleName().toString();
        String eventSimple = observer.eventType().getSimpleName().toString();
        String providerMethodName = "provide" + beanSimple + eventSimple + "_" + ordinal + "ObserverRegistration";

        // Parameter: the Dagger-injected bean instance
        ParameterSpec beanParam = ParameterSpec.builder(beanTypeName, "bean").build();

        // Body: new ObserverRegistration(EventType.class, priority, e -> bean.method((EventType) e))
        CodeBlock body = CodeBlock.builder()
                .addStatement(
                        "return new $T($T.class, $L, e -> bean.$L(($T) e))",
                        OBSERVER_REGISTRATION,
                        eventTypeName,
                        observer.priority(),
                        methodSimple,
                        eventTypeName)
                .build();

        writer.addIntoSetProvidesWithBody(OBSERVER_REGISTRATION, providerMethodName, body, beanParam);
    }
}
