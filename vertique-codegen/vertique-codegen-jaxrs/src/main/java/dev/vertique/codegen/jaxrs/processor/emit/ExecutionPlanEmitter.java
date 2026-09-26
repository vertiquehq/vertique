// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs.processor.emit;

import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ArrayTypeName;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.jaxrs.EffectiveMethodContract;
import dev.vertique.codegen.jaxrs.EffectiveParamContract;
import dev.vertique.codegen.jaxrs.JaxRsHierarchy;
import dev.vertique.codegen.jaxrs.JaxRsParamSource;
import dev.vertique.codegen.support.Identifiers;
import jakarta.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.annotation.processing.Generated;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/**
 * Emitter for per-method {@code {Resource}_{methodName}_{idx}_ExecutionPlan} companions
 * (CG-010 step 4d, slice 2).
 *
 * <p>For each eligible verb-bearing method in a validated
 * {@link dev.vertique.codegen.jaxrs.EffectiveResourceContract}, this emitter generates a
 * {@code public final class {Resource}_{methodName}_{idx}_ExecutionPlan} in the resource's own
 * package. The generated class implements
 * {@link dev.vertique.rest.jaxrs.runtime.ResourceExecutionPlan} and replaces the two reflective
 * operations that happen on every request in {@code ResourceMethodInvoker}: reflective argument
 * extraction via {@code ParameterExtractor} and {@code Method.invoke(...)}.
 *
 * <h2>Generated class shape</h2>
 *
 * <p>Each plan is <em>self-contained</em>: it declares a
 * {@code private static final ResourceMethodMeta.ParamMeta P{n}} constant per extractable
 * parameter, assembled once at class-load time via a private static {@code loadClass} helper.
 * No constructor parameters are required. The descriptor's {@code describe()} method instantiates
 * the plan via its no-arg public constructor: {@code new XXX_ExecutionPlan()}.
 *
 * <h2>Eligibility gate</h2>
 *
 * <p>{@link #emit(TypeElement, EffectiveMethodContract, int, Set)} returns {@code null} without
 * emitting when any of the following hold:
 * <ul>
 *   <li>The concrete method is not {@code public} — non-public methods cannot be called directly
 *       from a generated companion in another package.</li>
 *   <li>Any parameter or return type is not accessible from the generated companion's package —
 *       approximated by checking whether the type element is not {@code PUBLIC} AND its enclosing
 *       package differs from the resource class's package.</li>
 *   <li>The method's declaring class is {@code java.lang.Object} (defensive; contract resolution
 *       already skips Object methods).</li>
 * </ul>
 *
 * <h2>EffectiveInputPolicies</h2>
 *
 * <p>Per-parameter {@code POL_i} constants are built from the pre-computed per-parameter policy
 * chains in {@link EffectiveParamContract#canonicalizers()} and
 * {@link EffectiveParamContract#sanitizers()}. Each chain already embeds the route-level baseline
 * overridden by any parameter-level {@code @Canonicalize}/{@code @Sanitize}/{@code @Skip*}
 * annotation: these are the chains {@link dev.vertique.input.processing.apt.ElementInvocationPolicies}
 * resolved at compile time, which are equal to what
 * {@link dev.vertique.input.processing.ReflectiveInvocationPolicies} derives at runtime for the same
 * declarations, so the emitted constant faithfully carries the plan's policies.
 *
 * <p>For {@code BEAN_PARAM} parameters, only the route-level {@code ROUTE_POL} constant is
 * emitted. Per-field policies are derived at materialisation time inside
 * {@code ParameterExtractor.materializeBean} from each field's
 * {@link dev.vertique.rest.jaxrs.ResourceMethodMeta.ParamMeta#annotations()} array, which is
 * populated by the generated {@code _BeanParamModel} companion via
 * {@code loadFieldAnnotations} / {@code loadRecordComponentAnnotations}.
 */
public final class ExecutionPlanEmitter {

    // --- Well-known FQN strings (no runtime dep at compile time in this module) ---

    private static final String PROCESSOR_FQN = "dev.vertique.codegen.jaxrs.JaxRsPipelineProcessor";

    // --- JavaPoet ClassName constants ---

    private static final ClassName EXECUTION_PLAN =
            ClassName.get("dev.vertique.rest.jaxrs.runtime", "ResourceExecutionPlan");
    private static final ClassName GENERATED_JAXRS_SUPPORT =
            ClassName.get("dev.vertique.rest.jaxrs.runtime", "GeneratedJaxRsSupport");
    private static final ClassName BEAN_PARAM_FIELD_META =
            ClassName.get("dev.vertique.rest.jaxrs.runtime", "BeanParamFieldMeta");
    private static final ClassName BEAN_PARAM_MODEL =
            ClassName.get("dev.vertique.rest.jaxrs.runtime", "GeneratedJaxRsBeanParamModel");
    private static final ClassName BEAN_PARAM_REGISTRY =
            ClassName.get("dev.vertique.rest.jaxrs.runtime", "GeneratedJaxRsBeanParamRegistry");
    private static final ClassName PARAM_META =
            ClassName.get("dev.vertique.rest.jaxrs", "ResourceMethodMeta", "ParamMeta");
    private static final ClassName PARAM_SOURCE =
            ClassName.get("dev.vertique.rest.jaxrs", "ResourceMethodMeta", "ParamSource");
    private static final ClassName EFFECTIVE_INPUT_POLICIES =
            ClassName.get("dev.vertique.input.processing", "EffectiveInputPolicies");
    private static final ClassName REQUEST_PRECONDITIONS =
            ClassName.get("dev.vertique.rest.core.request", "RequestPreconditions");
    private static final ClassName ROUTING_CONTEXT = ClassName.get("io.vertx.ext.web", "RoutingContext");
    private static final ClassName BOUND_REQUEST = ClassName.get("dev.vertique.rest.jaxrs.request", "BoundRequest");
    private static final ClassName LIST = ClassName.get("java.util", "List");

    // --- State ---

    private final CodegenContext ctx;
    private final ParameterAnnotationMaterializer parameterAnnotationMaterializer;

    // --- Constructor ---

    /**
     * Creates an emitter bound to the given codegen context.
     *
     * @param ctx the shared codegen context; must not be {@code null}
     */
    public ExecutionPlanEmitter(CodegenContext ctx) {
        this.ctx = ctx;
        this.parameterAnnotationMaterializer = new ParameterAnnotationMaterializer(ctx);
    }

    // --- Public API ---

    /**
     * Emits the execution-plan companion for a single verb-bearing method, if eligible.
     *
     * <p>Returns the {@link ClassName} of the emitted plan class (e.g.
     * {@code UserResource_getUser_0_ExecutionPlan}) or {@code null} when the method is not
     * eligible for plan emission (see class-level javadoc). The descriptor emitter uses the
     * returned value to decide whether to pass a {@code new XXX_ExecutionPlan()} instance as the
     * {@code executionPlan} argument in the generated {@code ResourceMethodMeta} constructor call.
     *
     * @param concreteClass       the resource class element; must not be {@code null}
     * @param method              the effective method contract; must not be {@code null}; must be a
     *                            verb-bearing method ({@link EffectiveMethodContract#httpMethod()}
     *                            non-null)
     * @param methodIndex         zero-based index disambiguating overloaded methods with the same name
     * @param emittedLiteralFqns  the per-round shared dedup set of {@code <Ann>$JaxRsLiteral} FQNs already
     *                            written to the {@code Filer}; this method adds each literal class it
     *                            writes and skips any already present (per-compilation dedup)
     * @return the {@link ClassName} of the emitted plan, or {@code null} if not eligible
     */
    @Nullable
    public ClassName emit(
            TypeElement concreteClass,
            EffectiveMethodContract method,
            int methodIndex,
            Set<String> emittedLiteralFqns) {
        // --- Eligibility gate ---
        if (!isEligible(concreteClass, method)) {
            return null;
        }

        String pkg = ctx.packageNameOf(concreteClass);
        String methodName = method.concreteMethod().getSimpleName().toString();
        String planSimpleName =
                Identifiers.generatedClassName(concreteClass, "_" + methodName + "_" + methodIndex + "_ExecutionPlan");

        // ClassName.get(TypeElement) yields the source-form nested name (Outer.InnerResource)
        // when the resource is a static nested class. JaxRsCandidateScanner only surfaces top-level
        // root elements today, but the four CG-010 emitters use this same idiom for consistency.
        ClassName resourceClass = ClassName.get(concreteClass);
        ClassName planClass = ClassName.get(pkg, planSimpleName);

        List<EffectiveParamContract> params = method.params();
        boolean hasBeanParam = params.stream().anyMatch(p -> p.source() == JaxRsParamSource.BEAN_PARAM);

        // Declaring class FQN — used as the resourceClass argument in resolveContext calls so
        // error messages match what the runtime passes via meta.method().getDeclaringClass().getName().
        String declaringClassFqn = ((TypeElement) method.concreteMethod().getEnclosingElement())
                .getQualifiedName()
                .toString();

        // --- Static ParamMeta constants per extractable parameter ---
        List<FieldSpec> staticFields =
                buildParamMetaConstants(params, method.concreteMethod(), planClass, emittedLiteralFqns);

        // --- Static EffectiveInputPolicies constants ---
        staticFields.addAll(buildPoliciesConstants(params));

        // --- Static Class<?> constants for CONTEXT parameters ---
        staticFields.addAll(buildContextClassConstants(params));

        // --- Static route-level EffectiveInputPolicies constant for BEAN_PARAM materialization ---
        if (hasBeanParam) {
            staticFields.add(buildRoutePoliciesConstant(method));
        }

        // --- extractArguments(...) method ---
        MethodSpec extractArguments = buildExtractArguments(params, hasBeanParam, methodName, declaringClassFqn);

        // --- invoke(...) method ---
        MethodSpec invoke = buildInvoke(method, resourceClass);

        // --- private static helpers emitted into generated class ---
        List<MethodSpec> helpers = new ArrayList<>();
        helpers.add(buildLoadClassHelper(planClass));

        // --- assemble class ---
        TypeSpec.Builder classBuilder = TypeSpec.classBuilder(planSimpleName)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addAnnotation(AnnotationSpec.builder(ClassName.get(Generated.class))
                        .addMember("value", "$S", PROCESSOR_FQN)
                        .build())
                .addSuperinterface(EXECUTION_PLAN);

        for (FieldSpec sf : staticFields) {
            classBuilder.addField(sf);
        }
        classBuilder.addMethod(extractArguments);
        classBuilder.addMethod(invoke);
        for (MethodSpec h : helpers) {
            classBuilder.addMethod(h);
        }

        JavaFile javaFile = JavaFile.builder(pkg, classBuilder.build()).build();

        try {
            javaFile.writeTo(ctx.filer());
            return planClass;
        } catch (IOException e) {
            ctx.diagnostics().error(concreteClass, "Failed to write %s: %s", planClass.canonicalName(), e.getMessage());
            return null;
        }
    }

    // --- Private: eligibility gate ---

    /**
     * Returns {@code true} when the method and all its parameter/return types are directly callable
     * from generated code in the resource's package.
     *
     * <p>Gates:
     * <ol>
     *   <li>The concrete method must be {@code public}.</li>
     *   <li>The method's declaring class must not be {@code java.lang.Object}.</li>
     *   <li>No parameter or return type may be a non-public type whose enclosing package differs
     *       from the resource class's package.</li>
     *   <li>Each declared parameter type must erase to the same type as the parameter's type as a
     *       member of the resource. An inherited generic method — a {@code default remove(I id)} of
     *       {@code Crud<I>}, or a generic superclass method — declares {@code I}, which the
     *       reflective path binds as its erasure; a typed call {@code ((Resource) r).remove(...)}
     *       sees {@code remove(String)} instead and would not compile, so such a method keeps
     *       reflective dispatch at parity with the runtime.</li>
     *   <li>An inherited interface {@code default} method must not share its name and erased
     *       parameter types with a method declared along the resource's superclass chain. Such a
     *       method can only be a superclass's private one (anything else would override the
     *       default); the JVM resolves the typed call {@code ((Resource) r).m(...)} to it and throws
     *       {@code IllegalAccessError}, while reflective dispatch of the default's {@code Method}
     *       selects the default.</li>
     * </ol>
     *
     * @param concreteClass the resource class
     * @param method        the method contract
     * @return {@code true} if eligible for plan emission
     */
    private boolean isEligible(TypeElement concreteClass, EffectiveMethodContract method) {
        // Gate 1: method must be public
        if (!method.concreteMethod().getModifiers().contains(Modifier.PUBLIC)) {
            return false;
        }

        // Gate 2: declaring class must not be Object (defensive)
        TypeElement declaringClass = (TypeElement) method.concreteMethod().getEnclosingElement();
        if ("java.lang.Object".equals(declaringClass.getQualifiedName().toString())) {
            return false;
        }

        // Gate 3: all types must be accessible from the resource's package
        Elements elements = ctx.elements();
        String resourcePkg = ctx.packageNameOf(concreteClass);

        // Check return type
        TypeMirror returnType = method.concreteMethod().getReturnType();
        if (!isTypeAccessible(returnType, resourcePkg, elements)) {
            return false;
        }

        // Check each parameter type
        for (EffectiveParamContract pc : method.params()) {
            if (!isTypeAccessible(pc.type(), resourcePkg, elements)) {
                return false;
            }
        }

        // Gate 5: an inherited default shadowed at the JVM level by a same-signature superclass method
        if (method.concreteMethod().getEnclosingElement().getKind() == ElementKind.INTERFACE) {
            TypeElement current = concreteClass;
            while (current != null
                    && !"java.lang.Object".equals(current.getQualifiedName().toString())) {
                if (JaxRsHierarchy.findMatchingMethod(ctx, method.concreteMethod(), current) != null) {
                    return false;
                }
                current = JaxRsHierarchy.superClass(ctx, current);
            }
        }

        // Gate 4: declared parameter types must erase like their types as resource members
        Types types = ctx.types();
        ExecutableType memberType =
                (ExecutableType) types.asMemberOf((DeclaredType) concreteClass.asType(), method.concreteMethod());
        List<? extends VariableElement> declared = method.concreteMethod().getParameters();
        for (int i = 0; i < declared.size(); i++) {
            TypeMirror declaredErasure = types.erasure(declared.get(i).asType());
            TypeMirror memberErasure =
                    types.erasure(memberType.getParameterTypes().get(i));
            if (!types.isSameType(declaredErasure, memberErasure)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Returns {@code true} when the given type is accessible from code in {@code fromPackage}.
     *
     * <p>Primitive types and {@code void} are always accessible. For declared types: {@code PUBLIC}
     * types are accessible everywhere. For package-private or protected types, accessibility
     * requires the type to be in the same package as the generated companion.
     *
     * @param type        the type to check
     * @param fromPackage the package where the generated code lives
     * @param elements    the {@link Elements} utility from the processing environment
     * @return {@code true} if the type is accessible from {@code fromPackage}
     */
    private boolean isTypeAccessible(TypeMirror type, String fromPackage, Elements elements) {
        if (type == null) {
            return true;
        }
        TypeKind kind = type.getKind();
        // Primitive types, void, and null type are always accessible
        if (kind.isPrimitive() || kind == TypeKind.VOID || kind == TypeKind.NULL) {
            return true;
        }
        // Erase to get the declared element
        TypeMirror erased = ctx.types().erasure(type);
        var element = ctx.types().asElement(erased);
        if (!(element instanceof TypeElement te)) {
            // Array or other non-declared type — treat as accessible
            return true;
        }
        // PUBLIC types are accessible from any package
        if (te.getModifiers().contains(Modifier.PUBLIC)) {
            return true;
        }
        // Non-public: only accessible from the same package
        String typePkg = elements.getPackageOf(te).getQualifiedName().toString();
        return fromPackage.equals(typePkg);
    }

    // --- Private: static constant builders ---

    /**
     * Builds {@code private static final ResourceMethodMeta.ParamMeta P{n} = new ParamMeta(...)}
     * field specs for each extractable parameter.
     *
     * <p>These constants are assembled once at class-load time via the {@code loadClass} helper and
     * are passed directly to the typed support helper calls in {@code extractArguments}, avoiding
     * per-request {@code ParamMeta} allocation.
     *
     * <p>Only parameters whose source requires a {@code ParamMeta} constant get a field:
     * {@code PATH}, {@code QUERY}, {@code HEADER}, {@code COOKIE}, {@code FORM}, {@code BODY},
     * and {@code ENTITY_PARTS}. Parameters such as {@code CONTEXT}, {@code PRECONDITIONS},
     * {@code FILE_UPLOADS} do not need metadata.
     *
     * @param params              the parameter contracts in declaration order
     * @param concreteMethod      the enclosing concrete method (seeds each parameter's reflective
     *                            annotation fallback lookup — declaring class, name, param types)
     * @param planClass           the {@link ClassName} of the enclosing execution plan, used to name
     *                            the per-parameter standalone {@code ParameterMetadata} impls
     * @param emittedLiteralFqns  the per-round shared dedup set for {@code <Ann>$JaxRsLiteral} classes
     * @return the ordered list of field specs
     */
    private List<FieldSpec> buildParamMetaConstants(
            List<EffectiveParamContract> params,
            javax.lang.model.element.ExecutableElement concreteMethod,
            ClassName planClass,
            Set<String> emittedLiteralFqns) {
        List<FieldSpec> result = new ArrayList<>();
        for (int i = 0; i < params.size(); i++) {
            EffectiveParamContract pc = params.get(i);
            if (!paramNeedsParamMeta(pc.source())) {
                continue;
            }
            String constName = "P" + i;

            CodeBlock.Builder init = CodeBlock.builder();

            // name (preserve null vs blank parity — CG-009 blank-name parity)
            if (pc.name() == null) {
                init.add(
                        "new $T(null, $T.$L, $L, ",
                        PARAM_META,
                        PARAM_SOURCE,
                        pc.source().name(),
                        typeClassExpr(pc.type()));
            } else {
                init.add(
                        "new $T($S, $T.$L, $L, ",
                        PARAM_META,
                        pc.name(),
                        PARAM_SOURCE,
                        pc.source().name(),
                        typeClassExpr(pc.type()));
            }

            // componentType
            if (pc.componentType() != null) {
                init.add("$L, ", typeClassExpr(pc.componentType()));
            } else {
                init.add("null, ");
            }

            // genericType — emit TypeReference token for parameterized BODY types; null otherwise.
            // Jackson TypeReference is already on the runtime classpath for body deserialization.
            init.add("$L, ", buildGenericTypeExpr(pc));

            // defaultValue
            if (pc.defaultValue() != null) {
                init.add("$S, ", pc.defaultValue());
            } else {
                init.add("null, ");
            }

            // parameterMetadata — composed ParameterMetadata view, literal-first (GitHub issue #162):
            // runtime-retained parameter annotations are materialized at compile time into a standalone
            // ParameterMetadata implementation. A parameter whose annotations are all literalizable is
            // fully reflection-free; a parameter carrying an unliteralizable annotation (nested/float/
            // double/char member) additionally keeps a LAZY reflective fallback
            // (GeneratedJaxRsReflectiveAnnotations), consulted only when findAnnotation/annotationsLazy()
            // fires, so codegen stays byte-for-byte at parity with the reflective scan (ADR-0146) — do
            // NOT optimize the fallback away. Input policies still come from the precomputed POL{n}
            // constant; this view exists solely for findAnnotation/hasAnnotation/annotationsLazy()
            // consumers (e.g. a ParamConverterProvider bridged from JAX-RS).
            ClassName paramMetaClass =
                    ClassName.get(planClass.packageName(), planClass.simpleName() + "_P" + i + "Meta");
            CodeBlock parameterMetadataExpr = parameterAnnotationMaterializer.materialize(
                    pc, concreteMethod, i, paramMetaClass, emittedLiteralFqns, this::write);
            init.add("$L)", parameterMetadataExpr);

            result.add(FieldSpec.builder(PARAM_META, constName, Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                    .initializer(init.build())
                    .build());
        }
        return result;
    }

    /**
     * Writes a generated {@link JavaFile} via the shared {@link javax.annotation.processing.Filer},
     * routing an {@link IOException} to {@code Diagnostics.error} (mirrors
     * {@code AopProxyEmitter.write}).
     *
     * @param file the file to write
     */
    private void write(JavaFile file) {
        try {
            file.writeTo(ctx.filer());
        } catch (IOException e) {
            ctx.diagnostics()
                    .error(null, "Failed to write %s: %s", file.typeSpec().name(), e.getMessage());
        }
    }

    /**
     * Builds {@code private static final EffectiveInputPolicies POL{n}} field specs for each
     * parameter that uses a typed support-helper call requiring an
     * {@link dev.vertique.input.processing.EffectiveInputPolicies} argument
     * ({@code PATH}, {@code QUERY}, {@code HEADER}, {@code COOKIE}, {@code FORM}, {@code BODY}).
     *
     * <p>Each constant is built from the pre-computed per-parameter policy chains in
     * {@link EffectiveParamContract#canonicalizers()} and {@link EffectiveParamContract#sanitizers()},
     * which already embed route-level overrides and parameter-level annotation overrides. An empty
     * chain on both axes results in {@code EffectiveInputPolicies.NONE}; otherwise, a
     * {@code new EffectiveInputPolicies(List.of(...), List.of(...))} is emitted.
     *
     * @param params the parameter contracts in declaration order
     * @return the ordered list of field specs
     */
    private List<FieldSpec> buildPoliciesConstants(List<EffectiveParamContract> params) {
        List<FieldSpec> result = new ArrayList<>();
        for (int i = 0; i < params.size(); i++) {
            EffectiveParamContract pc = params.get(i);
            if (!paramNeedsPolicies(pc.source())) {
                continue;
            }
            String constName = "POL" + i;
            CodeBlock initializer = buildPoliciesInitializer(pc);
            result.add(FieldSpec.builder(
                            EFFECTIVE_INPUT_POLICIES, constName, Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                    .initializer(initializer)
                    .build());
        }
        return result;
    }

    /**
     * Builds the initializer {@link CodeBlock} for a {@code POL{n}} constant from the pre-computed
     * per-parameter policy chains.
     *
     * <p>When both chains are empty, emits {@code EffectiveInputPolicies.NONE}. Otherwise emits:
     * <pre>{@code
     *   new EffectiveInputPolicies(List.of(CanonClass.class, ...), List.of(SanitClass.class, ...))
     * }</pre>
     *
     * @param pc the parameter contract
     * @return the initializer code block
     */
    private CodeBlock buildPoliciesInitializer(EffectiveParamContract pc) {
        List<TypeMirror> canonChain = pc.canonicalizers();
        List<TypeMirror> sanitChain = pc.sanitizers();
        if (canonChain.isEmpty() && sanitChain.isEmpty()) {
            return CodeBlock.of("$T.NONE", EFFECTIVE_INPUT_POLICIES);
        }
        CodeBlock.Builder cb = CodeBlock.builder();
        cb.add("new $T(", EFFECTIVE_INPUT_POLICIES);
        cb.add(buildClassList(canonChain));
        cb.add(", ");
        cb.add(buildClassList(sanitChain));
        cb.add(")");
        return cb.build();
    }

    /**
     * Builds a {@code List.of(Foo.class, Bar.class)} code block from a list of {@link TypeMirror}
     * instances representing canonicalizer or sanitizer implementation classes.
     *
     * @param chain the list of type mirrors
     * @return the {@code List.of(...)} code block
     */
    private CodeBlock buildClassList(List<TypeMirror> chain) {
        CodeBlock.Builder cb = CodeBlock.builder();
        cb.add("$T.of(", LIST);
        for (int i = 0; i < chain.size(); i++) {
            if (i > 0) cb.add(", ");
            TypeMirror tm = chain.get(i);
            var element = ctx.types().asElement(ctx.types().erasure(tm));
            if (element instanceof TypeElement te) {
                // Use ClassName.get to produce valid source form (Outer.Inner, not Outer$Inner)
                cb.add("$T.class", ClassName.get(te));
            } else {
                // Fallback: use erasedFqn for primitive/array types (should not happen for classes)
                cb.add("loadClass($S)", erasedFqn(tm));
            }
        }
        cb.add(")");
        return cb.build();
    }

    /**
     * Builds the {@code genericType} expression for a parameter's {@code ParamMeta} constructor
     * argument in the execution plan.
     *
     * <p>For {@link JaxRsParamSource#BODY} parameters with a parameterized {@link DeclaredType},
     * emits an anonymous Jackson {@code TypeReference} subclass literal that captures the full
     * generic type. For non-body or non-parameterized types, emits {@code null}.
     *
     * @param pc the parameter contract
     * @return the code block expression ({@code null} literal or TypeReference expression)
     */
    private CodeBlock buildGenericTypeExpr(EffectiveParamContract pc) {
        if (pc.genericType() == null || pc.source() != JaxRsParamSource.BODY) {
            return CodeBlock.of("null");
        }
        if (!(pc.genericType() instanceof DeclaredType dt)
                || dt.getTypeArguments().isEmpty()) {
            return CodeBlock.of("null");
        }
        String sourceType = sourceTypeName(pc.genericType());
        return CodeBlock.of("new com.fasterxml.jackson.core.type.TypeReference<$L>() {}.getType()", sourceType);
    }

    /**
     * Returns the source-form type name (qualified name with type arguments, not binary) for
     * embedding in a TypeReference literal.
     *
     * <p>Non-declared types (notably array type arguments such as the {@code Outer.Inner[]} in
     * {@code List<Outer.Inner[]>}) go through
     * {@link TypeMirrorFqn#erasedSourceFqn(TypeMirror, dev.vertique.codegen.CodegenContext)}, not
     * {@link TypeMirrorFqn#erasedFqn(TypeMirror, dev.vertique.codegen.CodegenContext)}: this string
     * is interpolated into the generated source, where a binary {@code Outer$Inner} base name would
     * not compile.
     *
     * @param type the type mirror to render
     * @return the source-form type name string
     */
    private String sourceTypeName(TypeMirror type) {
        if (!(type instanceof DeclaredType dt)) {
            return erasedSourceFqn(type);
        }
        var element = ctx.types().asElement(ctx.types().erasure(type));
        String rawName =
                element instanceof TypeElement te ? te.getQualifiedName().toString() : erasedSourceFqn(type);
        var args = dt.getTypeArguments();
        if (args.isEmpty()) {
            return rawName;
        }
        StringBuilder sb = new StringBuilder(rawName).append('<');
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(sourceTypeName(args.get(i)));
        }
        sb.append('>');
        return sb.toString();
    }

    // --- Private: interface method builders ---

    /**
     * Builds {@code private static final Class<?> CTX{i} = loadClass("...")} field specs for
     * each parameter whose source is {@link JaxRsParamSource#CONTEXT}.
     *
     * <p>These constants are assembled once at class-load time via the {@code loadClass} helper,
     * avoiding a per-request {@code Class.forName} call in the generated {@code extractArguments}
     * body. The field type is {@code Class<?>} (wildcard-bounded) matching the signature of
     * {@link dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsSupport#resolveContext}.
     *
     * @param params the parameter contracts in declaration order
     * @return the ordered list of field specs (empty when no CONTEXT params are present)
     */
    private List<FieldSpec> buildContextClassConstants(List<EffectiveParamContract> params) {
        ParameterizedTypeName classOfQ = ParameterizedTypeName.get(
                ClassName.get(Class.class), com.palantir.javapoet.WildcardTypeName.subtypeOf(Object.class));
        List<FieldSpec> result = new ArrayList<>();
        for (int i = 0; i < params.size(); i++) {
            EffectiveParamContract pc = params.get(i);
            if (pc.source() != JaxRsParamSource.CONTEXT) {
                continue;
            }
            String constName = "CTX" + i;
            CodeBlock initializer = CodeBlock.of("loadClass($S)", erasedFqn(pc.type()));
            result.add(FieldSpec.builder(classOfQ, constName, Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                    .initializer(initializer)
                    .build());
        }
        return result;
    }

    /**
     * Builds the {@code extractArguments(RoutingContext, BoundRequest, GeneratedJaxRsSupport)}
     * override that dispatches each parameter to the appropriate typed support helper call.
     *
     * @param params              the parameter contracts in declaration order
     * @param hasBeanParam        whether any parameter is a {@code BEAN_PARAM}
     * @param methodName          the simple name of the resource method, passed to
     *                            {@code resolveContext} for diagnostic messages
     * @param declaringClassFqn   the fully-qualified name of the declaring class, passed to
     *                            {@code resolveContext} for diagnostic messages; must match
     *                            {@code meta.method().getDeclaringClass().getName()} at runtime
     * @return the method spec
     */
    private MethodSpec buildExtractArguments(
            List<EffectiveParamContract> params, boolean hasBeanParam, String methodName, String declaringClassFqn) {
        CodeBlock.Builder body = CodeBlock.builder();

        body.add("return new $T[] {\n", Object.class);
        body.indent();

        for (int i = 0; i < params.size(); i++) {
            EffectiveParamContract pc = params.get(i);
            boolean last = (i == params.size() - 1);
            body.add(buildParamExtraction(pc, i, last, methodName, declaringClassFqn));
        }

        body.unindent();
        body.addStatement("}");

        return MethodSpec.methodBuilder("extractArguments")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(ArrayTypeName.of(Object.class))
                .addParameter(ROUTING_CONTEXT, "ctx")
                .addParameter(BOUND_REQUEST, "req")
                .addParameter(GENERATED_JAXRS_SUPPORT, "support")
                .addException(Exception.class)
                .addCode(body.build())
                .build();
    }

    /**
     * Builds the code block for a single parameter extraction expression.
     *
     * <p>Dispatches based on the compile-time {@link JaxRsParamSource}:
     * <ul>
     *   <li>{@code CONTEXT} → {@code support.resolveContext(CTX{i}, ctx, declaringClassFqn, methodName)};
     *       a {@code private static final Class<?> CTX{i}} constant holds the erased declared type,
     *       assembled once at class-load time (no per-request {@code Class.forName}).</li>
     *   <li>{@code PRECONDITIONS} → {@code RequestPreconditions.from(ctx)}</li>
     *   <li>{@code PATH}/{@code QUERY}/{@code HEADER}/{@code COOKIE} →
     *       typed cast of {@code support.extractScalarParam(P{i}, POL{i}, req)}</li>
     *   <li>{@code FORM} → typed cast of {@code support.extractFormParam(P{i}, POL{i}, ctx)}</li>
     *   <li>{@code BODY} → typed cast of {@code support.deserializeBody(P{i}, POL{i}, ctx)}</li>
     *   <li>{@code FILE_UPLOADS} → {@code support.extractFileUploads(ctx)}</li>
     *   <li>{@code ENTITY_PARTS} → {@code support.extractEntityParts(P{i}, ctx)}</li>
     *   <li>{@code BEAN_PARAM} → {@code support.materializeBean(...)} via registry lookup</li>
     * </ul>
     *
     * @param pc                the parameter contract
     * @param index             the zero-based parameter index
     * @param last              whether this is the last parameter (no trailing comma)
     * @param methodName        simple method name for {@code resolveContext} diagnostic argument
     * @param declaringClassFqn declaring class FQN for {@code resolveContext} diagnostic argument
     * @return the code block
     */
    private CodeBlock buildParamExtraction(
            EffectiveParamContract pc, int index, boolean last, String methodName, String declaringClassFqn) {
        CodeBlock.Builder cb = CodeBlock.builder();
        TypeName castType = typeNameForCast(pc.type());

        switch (pc.source()) {
            case CONTEXT -> cb.add("support.resolveContext(CTX$L, ctx, $S, $S)", index, declaringClassFqn, methodName);

            case PRECONDITIONS -> cb.add("$T.from(ctx)", REQUEST_PRECONDITIONS);

            case PATH, QUERY, HEADER, COOKIE -> {
                if (castType != null) {
                    cb.add("($T) support.extractScalarParam(P$L, POL$L, req)", castType, index, index);
                } else {
                    cb.add("support.extractScalarParam(P$L, POL$L, req)", index, index);
                }
            }

            case FORM -> {
                if (castType != null) {
                    cb.add("($T) support.extractFormParam(P$L, POL$L, ctx)", castType, index, index);
                } else {
                    cb.add("support.extractFormParam(P$L, POL$L, ctx)", index, index);
                }
            }

            case BODY -> {
                if (castType != null) {
                    cb.add("($T) support.deserializeBody(P$L, POL$L, ctx)", castType, index, index);
                } else {
                    cb.add("support.deserializeBody(P$L, POL$L, ctx)", index, index);
                }
            }

            case FILE_UPLOADS -> cb.add("support.extractFileUploads(ctx)");

            case ENTITY_PARTS -> cb.add("support.extractEntityParts(P$L, ctx)", index);

            case BEAN_PARAM -> cb.add(buildBeanParamExtraction(pc));
        }

        if (!last) {
            cb.add(",\n");
        } else {
            cb.add("\n");
        }
        return cb.build();
    }

    /**
     * Builds the bean-param materialization expression.
     *
     * <p>Fetches the {@code BeanParamFieldMeta[]} array from the
     * {@link dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsBeanParamRegistry} and passes it to
     * {@code support.materializeBean(...)} along with the pre-computed route-level policies
     * constant ({@code ROUTE_POL}). Per-field policies are derived inside
     * {@code ParameterExtractor.materializeBean} from each field's
     * {@link dev.vertique.rest.jaxrs.ResourceMethodMeta.ParamMeta#annotations()} array, which is
     * populated by the generated {@code _BeanParamModel} companion at class-load time via
     * {@code loadFieldAnnotations} / {@code loadRecordComponentAnnotations}.
     *
     * @param pc the BEAN_PARAM parameter contract
     * @return the code block for bean materialization
     */
    private CodeBlock buildBeanParamExtraction(EffectiveParamContract pc) {
        TypeMirror beanType = pc.beanParamType() != null ? pc.beanParamType() : pc.type();
        // Resolve the bean ClassName via TypeElement so nested classes emit the source-form
        // (Outer.Inner), not the binary form (Outer$Inner) which is only valid in Class.forName.
        // Bean params are always declared reference types, so typeNameForCast yields a ClassName here.
        TypeName beanClass = typeNameForCast(beanType);
        if (beanClass == null) {
            // Defensive — bean params should always be reference types.
            beanClass = ClassName.bestGuess(erasedFqn(beanType));
        }

        // Build the fields array expression: look up from registry, fall back to empty array
        CodeBlock fieldsExpr = CodeBlock.of(
                "$T.shared().lookup($T.class).map($T::fields)" + ".map(f -> f.toArray(new $T[0])).orElse(new $T[0])",
                BEAN_PARAM_REGISTRY,
                beanClass,
                BEAN_PARAM_MODEL,
                BEAN_PARAM_FIELD_META,
                BEAN_PARAM_FIELD_META);

        CodeBlock.Builder cb = CodeBlock.builder();
        cb.add("support.materializeBean(\n");
        cb.indent();
        cb.add("$L,\n", fieldsExpr);
        // Route-level policies for bean-as-structured-body — use pre-computed constant.
        // Per-field policies are computed inside materializeBean from field annotations.
        cb.add("ROUTE_POL,\n");
        cb.add("req, ctx, $T.class)", beanClass);
        cb.unindent();
        return cb.build();
    }

    /**
     * Builds the {@code private static final EffectiveInputPolicies ROUTE_POL} constant holding
     * the route-level input policies for this execution plan. Used by bean-param materialization
     * as the {@code routePolicies} argument to {@code support.materializeBean(...)}.
     *
     * @param method the method contract carrying route-level chains
     * @return the field spec
     */
    private FieldSpec buildRoutePoliciesConstant(EffectiveMethodContract method) {
        List<TypeMirror> canonChain = method.routeCanonicalizers();
        List<TypeMirror> sanitChain = method.routeSanitizers();
        CodeBlock initializer;
        if (canonChain.isEmpty() && sanitChain.isEmpty()) {
            initializer = CodeBlock.of("$T.NONE", EFFECTIVE_INPUT_POLICIES);
        } else {
            CodeBlock.Builder cb = CodeBlock.builder();
            cb.add("new $T(", EFFECTIVE_INPUT_POLICIES);
            cb.add(buildClassList(canonChain));
            cb.add(", ");
            cb.add(buildClassList(sanitChain));
            cb.add(")");
            initializer = cb.build();
        }
        return FieldSpec.builder(
                        EFFECTIVE_INPUT_POLICIES, "ROUTE_POL", Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                .initializer(initializer)
                .build();
    }

    /**
     * Builds the {@code invoke(Object resource, Object[] args)} override that calls the resource
     * method directly via a typed cast, eliminating reflective dispatch overhead and
     * {@link java.lang.reflect.InvocationTargetException} wrapping from the hot path.
     *
     * @param method        the effective method contract
     * @param resourceClass the resource class name
     * @return the method spec
     */
    private MethodSpec buildInvoke(EffectiveMethodContract method, ClassName resourceClass) {
        List<EffectiveParamContract> params = method.params();
        String methodName = method.concreteMethod().getSimpleName().toString();

        CodeBlock.Builder invokeExpr = CodeBlock.builder();
        invokeExpr.add("(($T) resource).$L(", resourceClass, methodName);
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) {
                invokeExpr.add(", ");
            }
            EffectiveParamContract pc = params.get(i);
            TypeName castType = typeNameForCast(pc.type());
            if (castType != null) {
                invokeExpr.add("($T) args[$L]", castType, i);
            } else {
                invokeExpr.add("args[$L]", i);
            }
        }
        invokeExpr.add(")");

        // Determine if the JVM method signature returns void. We check the RAW return type, not
        // the Future-unwrapped one: a Future<Void>-returning method is NOT void at the JVM level —
        // it returns a Future and the plan must return that Future so ResourceMethodInvoker can
        // hand it to .compose(...). Only methods with literal `void` (or `Void`) returns are void.
        TypeMirror rawReturn = method.concreteMethod().getReturnType();
        boolean returnsVoid = isVoidType(rawReturn);

        MethodSpec.Builder builder = MethodSpec.methodBuilder("invoke")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(Object.class)
                .addParameter(Object.class, "resource")
                .addParameter(ArrayTypeName.of(Object.class), "args")
                .addException(Throwable.class);

        if (returnsVoid) {
            builder.addStatement("$L", invokeExpr.build());
            builder.addStatement("return null");
        } else {
            builder.addStatement("return $L", invokeExpr.build());
        }

        return builder.build();
    }

    // --- Private: helper methods emitted into the generated class ---

    /**
     * Builds the {@code loadClass(String fqn)} private static helper emitted into the generated
     * plan class. Used by static {@code P{n}} field initializers to resolve {@code Class<?>}
     * instances from FQN strings at class-load time.
     *
     * <p>The thread context class loader is tried first, then — additively — the generated plan
     * class's own loader, mirroring the belt-and-braces pair the descriptor emitter already uses
     * ({@code resource.getClass().getClassLoader()} with a context-loader fallback). The plan class
     * is compiled into the same artifact as the resource it dispatches, so its own loader always
     * sees every parameter type in that resource's signatures — including a type nested inside the
     * resource itself — whereas the context loader is whatever the deploying thread happens to
     * carry. The fallback is purely additive: every FQN that resolved through the context loader
     * before still resolves to the same class.
     *
     * @param planClass the generated plan class's own name, used to reach its class loader
     * @return the method spec
     */
    private static MethodSpec buildLoadClassHelper(ClassName planClass) {
        ParameterizedTypeName classOfQ = ParameterizedTypeName.get(
                ClassName.get(Class.class), com.palantir.javapoet.WildcardTypeName.subtypeOf(Object.class));
        return MethodSpec.methodBuilder("loadClass")
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(classOfQ)
                .addParameter(String.class, "fqn")
                .addStatement("$T tccl_ = $T.currentThread().getContextClassLoader()", ClassLoader.class, Thread.class)
                .beginControlFlow("if (tccl_ != null)")
                .beginControlFlow("try")
                .addStatement("return $T.forName(fqn, false, tccl_)", Class.class)
                .nextControlFlow("catch ($T ignored)", ClassNotFoundException.class)
                .addCode("// Fall through to this plan class's own loader.\n")
                .endControlFlow()
                .endControlFlow()
                .beginControlFlow("try")
                .addStatement("return $T.forName(fqn, false, $T.class.getClassLoader())", Class.class, planClass)
                .nextControlFlow("catch ($T e)", ClassNotFoundException.class)
                .addStatement("throw new $T(e)", IllegalStateException.class)
                .endControlFlow()
                .build();
    }

    // --- Private: classification helpers ---

    /**
     * Returns {@code true} for parameter sources that require a precomputed
     * {@code ResourceMethodMeta.ParamMeta} constant ({@code P{n}}).
     *
     * @param source the parameter source
     * @return {@code true} if a {@code P{n}} constant must be emitted for this parameter
     */
    private static boolean paramNeedsParamMeta(JaxRsParamSource source) {
        return switch (source) {
            case PATH, QUERY, HEADER, COOKIE, FORM, BODY, ENTITY_PARTS -> true;
            default -> false;
        };
    }

    /**
     * Returns {@code true} for parameter sources whose support-helper call requires an
     * {@link dev.vertique.input.processing.EffectiveInputPolicies} argument — implying that a
     * {@code POL{n}} constant must also be emitted.
     *
     * @param source the parameter source
     * @return {@code true} if a {@code POL{n}} constant must be emitted
     */
    private static boolean paramNeedsPolicies(JaxRsParamSource source) {
        return switch (source) {
            case PATH, QUERY, HEADER, COOKIE, FORM, BODY -> true;
            default -> false;
        };
    }

    // --- Private: type helpers ---

    /**
     * Returns a {@link CodeBlock} expression that evaluates to a {@code Class<?>} for the given
     * type, suitable for use as a method argument.
     *
     * <p>For primitive types, uses {@code int.class}, {@code long.class}, etc. as class literals —
     * {@link Class#forName(String)} cannot load primitive types by name. For reference types, calls
     * the emitted {@code loadClass(String)} helper.
     *
     * @param type the type mirror; may be {@code null} (treated as {@code loadClass("java.lang.Object")})
     * @return the class expression code block
     */
    private CodeBlock typeClassExpr(TypeMirror type) {
        if (type != null) {
            TypeKind kind = ctx.types().erasure(type).getKind();
            if (kind.isPrimitive()) {
                // Emit a .class literal for primitive types
                String primitiveStr =
                        switch (kind) {
                            case INT -> "int";
                            case LONG -> "long";
                            case DOUBLE -> "double";
                            case FLOAT -> "float";
                            case BOOLEAN -> "boolean";
                            case BYTE -> "byte";
                            case SHORT -> "short";
                            case CHAR -> "char";
                            default -> "void";
                        };
                return CodeBlock.of("$L.class", primitiveStr);
            }
        }
        // Array types: emit an array class literal (e.g. String[].class / int[].class) directly.
        // loadClass(fqn) would fail because Class.forName("java.lang.String[]", ...) rejects the
        // Java source array form (it needs the JVM binary form [Ljava.lang.String;). An array
        // class literal is reflection-free and correct for every element type and dimension.
        if (type != null && ctx.types().erasure(type).getKind() == TypeKind.ARRAY) {
            return CodeBlock.of("$T.class", TypeName.get(ctx.types().erasure(type)));
        }
        // Reference type or null — use loadClass helper
        return CodeBlock.of("loadClass($S)", erasedFqn(type));
    }

    /**
     * Returns the erased binary FQN of {@code type} — for strings the generated code resolves at
     * runtime. Delegates to {@link TypeMirrorFqn} so the three CG-010 emitters share one source of
     * truth on nested-type and array form.
     */
    private String erasedFqn(TypeMirror type) {
        return TypeMirrorFqn.erasedFqn(type, ctx);
    }

    /**
     * Returns the erased source-form FQN of {@code type} — for strings interpolated into the
     * generated source rather than resolved at runtime. Delegates to {@link TypeMirrorFqn}.
     */
    private String erasedSourceFqn(TypeMirror type) {
        return TypeMirrorFqn.erasedSourceFqn(type, ctx);
    }

    /**
     * Returns a {@link TypeName} suitable for a cast expression in the generated code.
     *
     * <p>For reference types, returns the erased class name directly. For primitive types,
     * returns the corresponding boxed class name (e.g. {@code int} → {@code Integer}) so
     * that the generated cast from {@code Object} (the return type of {@code extractScalarParam})
     * compiles and auto-unboxes correctly when passed as a primitive method argument. For
     * array types (e.g. {@code String[]}, {@code int[]}, {@code String[][]}), returns the
     * corresponding {@link com.palantir.javapoet.ArrayTypeName} so the generated cast renders as
     * a valid Java array type ({@code (String[]) ...}) rather than tripping
     * {@link ClassName#bestGuess(String)}, which rejects the {@code "String[]"} string form.
     * Returns {@code null} only for {@code void} (which never appears as a parameter type).
     *
     * @param type the parameter type mirror
     * @return the type name to use in the cast, or {@code null} for void
     */
    @Nullable
    private TypeName typeNameForCast(TypeMirror type) {
        if (type == null) {
            return null;
        }
        TypeMirror erased = ctx.types().erasure(type);
        TypeKind kind = erased.getKind();
        if (kind == TypeKind.VOID) {
            return null;
        }
        // For primitive types, use the boxed wrapper — the generated cast then auto-unboxes
        if (kind.isPrimitive()) {
            return switch (kind) {
                case INT -> ClassName.get(Integer.class);
                case LONG -> ClassName.get(Long.class);
                case DOUBLE -> ClassName.get(Double.class);
                case FLOAT -> ClassName.get(Float.class);
                case BOOLEAN -> ClassName.get(Boolean.class);
                case BYTE -> ClassName.get(Byte.class);
                case SHORT -> ClassName.get(Short.class);
                case CHAR -> ClassName.get(Character.class);
                default -> null;
            };
        }
        // For declared types (including nested classes like Outer.Inner), build the ClassName
        // through the element so JavaPoet emits the dotted source form (Outer.Inner), not the
        // binary form (Outer$Inner) which is only valid in Class.forName(...) strings.
        var element = ctx.types().asElement(erased);
        if (element instanceof TypeElement te) {
            return ClassName.get(te);
        }
        // Arrays (and any other non-declared mirror) — let JavaPoet render the type directly.
        // TypeName.get produces an ArrayTypeName for arrays (e.g. String[] / int[] / String[][]),
        // which emits a valid Java cast; ClassName.bestGuess("String[]") would throw instead.
        return TypeName.get(erased);
    }

    /**
     * Returns {@code true} if the type mirror represents {@code void} or {@code java.lang.Void}.
     *
     * @param type the type mirror to inspect
     * @return {@code true} for void/Void types
     */
    private boolean isVoidType(TypeMirror type) {
        if (type == null) {
            return false;
        }
        if (type.getKind() == TypeKind.VOID) {
            return true;
        }
        String fqn = erasedFqn(type);
        return "java.lang.Void".equals(fqn);
    }
}
