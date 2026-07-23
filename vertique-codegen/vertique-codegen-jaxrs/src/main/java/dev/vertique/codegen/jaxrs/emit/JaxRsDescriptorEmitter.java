// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs.emit;

import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ArrayTypeName;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeSpec;
import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.jaxrs.EffectiveMethodContract;
import dev.vertique.codegen.jaxrs.EffectiveParamContract;
import dev.vertique.codegen.jaxrs.EffectiveResourceContract;
import dev.vertique.codegen.jaxrs.EffectiveSecurityContract;
import dev.vertique.codegen.jaxrs.EffectiveSecurityContract.SecurityKind;
import dev.vertique.codegen.jaxrs.JaxRsParamSource;
import dev.vertique.codegen.support.Identifiers;
import jakarta.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.annotation.processing.Generated;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

/**
 * Emitter for per-resource {@code {Resource}_JaxRsDescriptor} companions (CG-010 step 4b).
 *
 * <p>For each validated {@link EffectiveResourceContract}, this emitter generates a
 * {@code public final class {Resource}_JaxRsDescriptor} in the resource's own package. The
 * generated class implements
 * {@code dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsResourceDescriptor<Resource>} and
 * pre-computes all per-method metadata as private static final constants to eliminate
 * per-request reflection.
 *
 * <h2>String-FQN constants</h2>
 *
 * <p>Every reference to a user-author type is emitted as a {@code private static final String}
 * (or {@code String[]}) constant and resolved at first-call via
 * {@link dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsDescriptorSupport}. This protects the
 * descriptor from inaccessible types (e.g. private nested classes, package-private validation
 * groups, or package-private canonicalizer/sanitizer implementations) that a direct {@code .class}
 * literal would prevent from compiling.
 *
 * <h2>Pre-computed SecurityPolicy</h2>
 *
 * <p>The JAX-RS security policy for each method is resolved at compile time from the
 * {@link EffectiveSecurityContract} (applying the Jakarta EE "method overrides class" rule) and
 * emitted as a {@code private static final SecurityPolicy SP_<methodName>} constant. Generated
 * descriptors never invoke any runtime security-resolver call; the constant is read directly.
 *
 * <h2>Sub-resource locators</h2>
 *
 * <p>Methods with no effective HTTP verb (sub-resource locators) are skipped entirely — no
 * {@code ResourceMethodMeta} entry is produced for them, matching runtime
 * {@code ResourceScanner.collectMethods → httpMethod == null → continue}.
 */
public final class JaxRsDescriptorEmitter {

    // --- Well-known FQNs (no runtime dep on rest-jaxrs at compile time in this module) ---

    private static final String DESCRIPTOR_FQN = "dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsResourceDescriptor";
    private static final String SUPPORT_FQN = "dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsDescriptorSupport";
    private static final String RESOURCE_METHOD_META_FQN = "dev.vertique.rest.jaxrs.ResourceMethodMeta";
    private static final String PARAM_META_FQN = "dev.vertique.rest.jaxrs.ResourceMethodMeta.ParamMeta";
    private static final String PARAM_SOURCE_FQN = "dev.vertique.rest.jaxrs.ResourceMethodMeta.ParamSource";
    private static final String MEDIA_TYPES_FQN = "dev.vertique.rest.jaxrs.ResourceMethodMeta.MediaTypes";
    private static final String SECURITY_POLICY_FQN = "dev.vertique.rest.core.security.SecurityPolicy";
    private static final String SECURITY_POLICY_VIOLATION_FQN =
            "dev.vertique.rest.core.security.SecurityPolicyViolation";
    private static final String PROCESSOR_FQN = "dev.vertique.codegen.jaxrs.JaxRsPipelineProcessor";

    // --- JavaPoet ClassName constants ---

    private static final ClassName DESCRIPTOR = ClassName.bestGuess(DESCRIPTOR_FQN);
    private static final ClassName SUPPORT =
            ClassName.get("dev.vertique.rest.jaxrs.runtime", "GeneratedJaxRsDescriptorSupport");
    private static final ClassName RESOURCE_METHOD_META =
            ClassName.get("dev.vertique.rest.jaxrs", "ResourceMethodMeta");
    private static final ClassName PARAM_META =
            ClassName.get("dev.vertique.rest.jaxrs", "ResourceMethodMeta", "ParamMeta");
    private static final ClassName PARAM_SOURCE =
            ClassName.get("dev.vertique.rest.jaxrs", "ResourceMethodMeta", "ParamSource");
    private static final ClassName MEDIA_TYPES =
            ClassName.get("dev.vertique.rest.jaxrs", "ResourceMethodMeta", "MediaTypes");
    private static final ClassName SECURITY_POLICY = ClassName.get("dev.vertique.rest.core.security", "SecurityPolicy");
    private static final ClassName SECURITY_POLICY_VIOLATION =
            ClassName.get("dev.vertique.rest.core.security", "SecurityPolicyViolation");
    private static final ClassName LIST = ClassName.get("java.util", "List");
    private static final ClassName METHOD = ClassName.get("java.lang.reflect", "Method");
    private static final ClassName ANNOTATION = ClassName.get("java.lang.annotation", "Annotation");

    // --- Nested SecurityPolicy variant names ---

    private static final ClassName SP_NONE = ClassName.get("dev.vertique.rest.core.security", "SecurityPolicy", "None");
    private static final ClassName SP_DENY_ALL =
            ClassName.get("dev.vertique.rest.core.security", "SecurityPolicy", "DenyAll");
    private static final ClassName SP_PERMIT_ALL =
            ClassName.get("dev.vertique.rest.core.security", "SecurityPolicy", "PermitAll");
    private static final ClassName SP_AUTHENTICATED_ONLY =
            ClassName.get("dev.vertique.rest.core.security", "SecurityPolicy", "AuthenticatedOnly");
    private static final ClassName SP_CONSTRAINED =
            ClassName.get("dev.vertique.rest.core.security", "SecurityPolicy", "Constrained");

    // --- State ---

    private final CodegenContext ctx;
    private final ParameterAnnotationMaterializer parameterAnnotationMaterializer;

    // --- Constructor ---

    /**
     * Creates an emitter bound to the given codegen context.
     *
     * @param ctx the shared codegen context; must not be {@code null}
     */
    public JaxRsDescriptorEmitter(CodegenContext ctx) {
        this.ctx = ctx;
        this.parameterAnnotationMaterializer = new ParameterAnnotationMaterializer(ctx);
    }

    // --- Public API ---

    /**
     * Emits the {@code {Resource}_JaxRsDescriptor} source file for the given validated contract.
     *
     * <p>The generated class is placed in the same package as the resource class. Sub-resource
     * locator methods (no effective HTTP verb) are omitted from the descriptor. When no verb-bearing
     * methods exist in the contract, the descriptor is still emitted with an empty
     * {@code describe()} return.
     *
     * <p>The {@code planClassNames} list is parallel to {@code contract.methods()} filtered to
     * verb-bearing methods. A non-null entry causes the descriptor to pass
     * {@code new XXX_ExecutionPlan()} as the {@code executionPlan} argument to
     * {@link dev.vertique.rest.jaxrs.ResourceMethodMeta}'s full constructor; a {@code null} entry
     * causes {@code null} to be passed (reflective path).
     *
     * @param contract            the validated effective resource contract; must not be {@code null}
     * @param planClassNames      parallel list of plan class names (elements may be {@code null}) for
     *                            each verb-bearing method; must have the same length as the list
     *                            returned by filtering {@code contract.methods()} to those with a
     *                            non-null {@code httpMethod}; must not be {@code null}
     * @param emittedLiteralFqns  the per-round shared dedup set of {@code <Ann>$Literal} FQNs already
     *                            written to the {@code Filer} (GitHub issue #162); this method adds
     *                            each literal class it writes and skips any already present
     */
    public void emit(
            EffectiveResourceContract contract, List<ClassName> planClassNames, Set<String> emittedLiteralFqns) {
        TypeElement concreteClass = contract.concreteClass();
        String pkg = ctx.packageNameOf(concreteClass);
        String simpleName = Identifiers.generatedClassName(concreteClass, "_JaxRsDescriptor");

        // ClassName.get(TypeElement) yields the source-form nested name (Outer.InnerResource)
        // when the resource is a static nested class. Today the candidate scanner only surfaces
        // top-level root elements, but using the element-aware overload keeps the four CG-010
        // emitters consistent.
        ClassName resourceClass = ClassName.get(concreteClass);
        ClassName descriptorClass = ClassName.get(pkg, simpleName);
        ParameterizedTypeName descriptorInterface = ParameterizedTypeName.get(DESCRIPTOR, resourceClass);

        // Collect only verb-bearing method contracts
        List<EffectiveMethodContract> verbMethods =
                contract.methods().stream().filter(m -> m.httpMethod() != null).toList();

        // --- Build static constants ---
        List<FieldSpec> staticFields = new ArrayList<>();
        // --- Build describe() method body ---
        CodeBlock.Builder describeBody = CodeBlock.builder();

        // Derive the classloader from the resource instance once per describe() call
        describeBody.addStatement("$T cl_ = resource.getClass().getClassLoader()", ClassLoader.class);
        describeBody.addStatement("if (cl_ == null) cl_ = $T.currentThread().getContextClassLoader()", Thread.class);

        // Method variable declarations and ResourceMethodMeta builder calls
        CodeBlock.Builder metaListBuilder = CodeBlock.builder();
        metaListBuilder.add("return $T.of(\n", LIST);
        metaListBuilder.indent();

        for (int methodIdx = 0; methodIdx < verbMethods.size(); methodIdx++) {
            EffectiveMethodContract mc = verbMethods.get(methodIdx);
            ExecutableElement concreteMethod = mc.concreteMethod();
            String methodName = concreteMethod.getSimpleName().toString();

            // --- Build per-method string FQN constants ---
            String methodSuffix = "_" + methodName + "_" + methodIdx;

            // Parameter type FQN constants: P0_, P1_, ...
            List<EffectiveParamContract> params = mc.params();
            for (int pIdx = 0; pIdx < params.size(); pIdx++) {
                EffectiveParamContract pc = params.get(pIdx);
                String paramTypeFqn = erasedFqn(pc.type());
                String constName = "P" + pIdx + methodSuffix;
                staticFields.add(stringConst(constName, paramTypeFqn));
            }

            // Return type FQN constant
            TypeMirror returnType = concreteMethod.getReturnType();
            TypeMirror unwrapped = ctx.unwrapFuture(returnType);
            String returnFqn = erasedFqn(unwrapped);
            staticFields.add(stringConst("R" + methodSuffix, returnFqn));

            // Validation groups: VG_ (only if present)
            String vgConstName = "VG" + methodSuffix;
            if (mc.validationGroups() != null && !mc.validationGroups().isEmpty()) {
                staticFields.add(stringArrayConst(
                        vgConstName,
                        mc.validationGroups().stream().map(this::erasedFqn).toList()));
            }

            // Canonicalizer chain: CC_ (non-empty when route-level @Canonicalize is present)
            // Sanitizer chain: SC_ (non-empty when route-level @Sanitize is present)
            String ccConstName = "CC" + methodSuffix;
            String scConstName = "SC" + methodSuffix;
            List<TypeMirror> routeCanons = mc.routeCanonicalizers();
            List<TypeMirror> routeSanits = mc.routeSanitizers();
            if (routeCanons.isEmpty()) {
                staticFields.add(emptyStringArrayConst(ccConstName));
            } else {
                staticFields.add(stringArrayConst(
                        ccConstName, routeCanons.stream().map(this::erasedFqn).toList()));
            }
            if (routeSanits.isEmpty()) {
                staticFields.add(emptyStringArrayConst(scConstName));
            } else {
                staticFields.add(stringArrayConst(
                        scConstName, routeSanits.stream().map(this::erasedFqn).toList()));
            }

            // SecurityPolicy constant
            String spConstName = "SP" + methodSuffix;
            EffectiveSecurityContract effective = effectiveSecurity(contract.classSecurity(), mc.methodSecurity());
            staticFields.add(securityPolicyConst(spConstName, effective));

            // --- Build method resolution and ResourceMethodMeta in describe() body ---
            // Method variable
            String methodVarName = "method" + methodIdx;
            describeBody.addStatement(
                    "$T $L = resolveMethod(support, cl_, resourceType(), $S" + buildParamFqnArgs(params, methodSuffix)
                            + ")",
                    METHOD,
                    methodVarName,
                    methodName);

            // ParamMeta list
            String paramListVarName = "params" + methodIdx;
            describeBody.add("$T<$T> $L = $T.of(\n", LIST, PARAM_META, paramListVarName, LIST);
            describeBody.indent();
            for (int pIdx = 0; pIdx < params.size(); pIdx++) {
                EffectiveParamContract pc = params.get(pIdx);
                String pConstName = "P" + pIdx + methodSuffix;
                describeBody.add(buildParamMeta(
                        pc,
                        pConstName,
                        methodVarName,
                        pIdx,
                        pIdx < params.size() - 1,
                        descriptorClass,
                        methodIdx,
                        concreteMethod,
                        emittedLiteralFqns));
            }
            describeBody.unindent();
            describeBody.addStatement(")");

            // Resolved types
            String returnVarName = "returnType" + methodIdx;
            String vgVarName = "vg" + methodIdx;
            String ccVarName = "cc" + methodIdx;
            String scVarName = "sc" + methodIdx;
            String methodAnnosVarName = "methodAnnos" + methodIdx;
            String classAnnosVarName = "classAnnos" + methodIdx;

            describeBody.addStatement(
                    "$T<?> $L = resolveClass(support, cl_, $L)", Class.class, returnVarName, "R" + methodSuffix);
            if (mc.validationGroups() != null && !mc.validationGroups().isEmpty()) {
                describeBody.addStatement(
                        "$T[] $L = resolveClasses(support, cl_, $L)", Class.class, vgVarName, vgConstName);
            } else {
                describeBody.addStatement("$T[] $L = null", Class.class, vgVarName);
            }
            describeBody.addStatement("var $L = resolveCanonicalizerChain(support, cl_, $L)", ccVarName, ccConstName);
            describeBody.addStatement("var $L = resolveSanitizerChain(support, cl_, $L)", scVarName, scConstName);
            describeBody.addStatement(
                    "$T<$T> $L = support.effectiveMethodAnnotations($L)",
                    LIST,
                    ANNOTATION,
                    methodAnnosVarName,
                    methodVarName);
            describeBody.addStatement(
                    "$T<$T> $L = support.effectiveClassAnnotations(resourceType())",
                    LIST,
                    ANNOTATION,
                    classAnnosVarName);

            // Compute returnsFuture and returnsVoid at compile time. Direct check via the future
            // detector — using TypeMirror.equals or Types.isSameType is unreliable for non-DeclaredType
            // mirrors (e.g. void returns NoType, where isSameType returns false even for the same
            // void). The semantic question is exactly "is the original return type a Future?".
            boolean returnsFuture = isFutureType(returnType);
            boolean returnsVoid = isVoidType(unwrapped);

            // Build ResourceMethodMeta
            String operationId = mc.operationId() != null ? mc.operationId() : methodName;
            String httpVerb = shortHttpVerb(mc.httpMethod());
            String fullPath = buildFullPath(contract.classPath(), mc.methodPath());

            @Nullable
            ClassName planClassName = methodIdx < planClassNames.size() ? planClassNames.get(methodIdx) : null;

            metaListBuilder.add(buildResourceMethodMeta(
                    methodVarName,
                    operationId,
                    httpVerb,
                    fullPath,
                    paramListVarName,
                    returnVarName,
                    returnsFuture,
                    returnsVoid,
                    spConstName,
                    mc,
                    ccVarName,
                    scVarName,
                    methodAnnosVarName,
                    classAnnosVarName,
                    vgVarName,
                    planClassName));

            if (methodIdx < verbMethods.size() - 1) {
                metaListBuilder.add(",\n");
            } else {
                metaListBuilder.add("\n");
            }
        }

        metaListBuilder.unindent();
        metaListBuilder.add(");\n");

        describeBody.add(metaListBuilder.build());

        // --- describe() method ---
        MethodSpec describeMethod = buildDescribeMethod(resourceClass, describeBody.build());

        // --- resourceType() method ---
        MethodSpec resourceTypeMethod = MethodSpec.methodBuilder("resourceType")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(ParameterizedTypeName.get(ClassName.get(Class.class), resourceClass))
                .addStatement("return $T.class", resourceClass)
                .build();

        // --- resolveMethod helper ---
        MethodSpec resolveMethodHelper = buildResolveMethodHelper();

        // --- resolveClass helper ---
        MethodSpec resolveClassHelper = buildResolveClassHelper();

        // --- resolveClasses helper ---
        MethodSpec resolveClassesHelper = buildResolveClassesHelper();

        // --- resolveCanonicalizerChain helper ---
        MethodSpec resolveCanonicalizerChainHelper = buildResolveCanonicalizerChainHelper();

        // --- resolveSanitizerChainHelper ---
        MethodSpec resolveSanitizerChainHelper = buildResolveSanitizerChainHelper();

        // --- assemble class ---
        TypeSpec.Builder classBuilder = TypeSpec.classBuilder(simpleName)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addAnnotation(AnnotationSpec.builder(ClassName.get(Generated.class))
                        .addMember("value", "$S", PROCESSOR_FQN)
                        .build())
                .addSuperinterface(descriptorInterface);

        for (FieldSpec sf : staticFields) {
            classBuilder.addField(sf);
        }
        classBuilder.addMethod(resourceTypeMethod);
        classBuilder.addMethod(describeMethod);
        classBuilder.addMethod(resolveMethodHelper);
        classBuilder.addMethod(resolveClassHelper);
        classBuilder.addMethod(resolveClassesHelper);
        classBuilder.addMethod(resolveCanonicalizerChainHelper);
        classBuilder.addMethod(resolveSanitizerChainHelper);

        JavaFile javaFile = JavaFile.builder(pkg, classBuilder.build()).build();

        try {
            javaFile.writeTo(ctx.filer());
        } catch (IOException e) {
            ctx.diagnostics()
                    .error(concreteClass, "Failed to write %s: %s", descriptorClass.canonicalName(), e.getMessage());
        }
    }

    // --- Private: constant builders ---

    /**
     * Builds a {@code private static final String CONST = "value";} field.
     *
     * @param name  the constant identifier
     * @param value the string value
     * @return the field spec
     */
    private static FieldSpec stringConst(String name, String value) {
        return FieldSpec.builder(String.class, name, Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                .initializer("$S", value)
                .build();
    }

    /**
     * Builds a {@code private static final String[] CONST = {"a", "b"};} field.
     *
     * @param name   the constant identifier
     * @param values the string array values
     * @return the field spec
     */
    private static FieldSpec stringArrayConst(String name, List<String> values) {
        CodeBlock.Builder init = CodeBlock.builder().add("new $T{", ArrayTypeName.of(String.class));
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                init.add(", ");
            }
            init.add("$S", values.get(i));
        }
        init.add("}");
        return FieldSpec.builder(
                        ArrayTypeName.of(String.class), name, Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                .initializer(init.build())
                .build();
    }

    /**
     * Builds a {@code private static final String[] CONST = new String[0];} field for
     * empty canonicalizer/sanitizer chains.
     *
     * @param name the constant identifier
     * @return the field spec
     */
    private static FieldSpec emptyStringArrayConst(String name) {
        return FieldSpec.builder(
                        ArrayTypeName.of(String.class), name, Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                .initializer("new $T[0]", String.class)
                .build();
    }

    /**
     * Builds a {@code private static final SecurityPolicy SP_x = ...;} field from the precomputed
     * effective security contract.
     *
     * <p>Applies Jakarta EE "method overrides class" rule: uses {@code methodSecurity} if non-empty,
     * falls back to {@code classSecurity}.
     *
     * @param name      the constant identifier
     * @param effective the already-merged effective security contract
     * @return the field spec
     */
    private static FieldSpec securityPolicyConst(String name, EffectiveSecurityContract effective) {
        CodeBlock init = buildSecurityPolicyInit(effective);
        return FieldSpec.builder(SECURITY_POLICY, name, Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                .initializer(init)
                .build();
    }

    // --- Private: security policy codegen ---

    /**
     * Returns the effective security contract for a method, applying Jakarta EE
     * "method overrides class" rule.
     *
     * @param classSecurity  class-level security contract
     * @param methodSecurity method-level security contract
     * @return the effective contract to use
     */
    private static EffectiveSecurityContract effectiveSecurity(
            EffectiveSecurityContract classSecurity, EffectiveSecurityContract methodSecurity) {
        return methodSecurity.isEmpty() ? classSecurity : methodSecurity;
    }

    /**
     * Builds the {@link CodeBlock} initializer expression for a {@link SecurityPolicy} instance
     * matching the given effective security contract.
     *
     * @param effective the effective security contract
     * @return the initializer code block
     */
    private static CodeBlock buildSecurityPolicyInit(EffectiveSecurityContract effective) {
        var kinds = effective.kinds();

        if (kinds.contains(SecurityKind.DENY_ALL)) {
            return CodeBlock.of("new $T()", SP_DENY_ALL);
        }
        if (kinds.contains(SecurityKind.PERMIT_ALL)) {
            return CodeBlock.of("new $T()", SP_PERMIT_ALL);
        }
        if (kinds.contains(SecurityKind.AUTHORIZED)
                && effective.authorizedScopes().isEmpty()
                && !kinds.contains(SecurityKind.ROLES_ALLOWED)) {
            return CodeBlock.of("new $T()", SP_AUTHENTICATED_ONLY);
        }
        if (kinds.contains(SecurityKind.ROLES_ALLOWED) || kinds.contains(SecurityKind.AUTHORIZED)) {
            // Constrained: build roles and scopes lists inline
            CodeBlock.Builder cb = CodeBlock.builder();
            cb.add("new $T(", SP_CONSTRAINED);
            cb.add(buildStringList(effective.rolesAllowed()));
            cb.add(", ");
            cb.add(buildStringList(effective.authorizedScopes()));
            cb.add(", $L)", effective.authorizedMatchAll());
            return cb.build();
        }
        // None
        return CodeBlock.of("new $T()", SP_NONE);
    }

    /**
     * Builds a {@code List.of("a", "b")} code block for the given string list.
     *
     * @param values the values to include
     * @return the code block
     */
    private static CodeBlock buildStringList(List<String> values) {
        CodeBlock.Builder cb = CodeBlock.builder();
        cb.add("$T.of(", LIST);
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                cb.add(", ");
            }
            cb.add("$S", values.get(i));
        }
        cb.add(")");
        return cb.build();
    }

    // --- Private: describe() body helpers ---

    /**
     * Builds a comma-separated string of {@code, P0_suffix, P1_suffix, ...} for
     * {@code resolveMethod} calls.
     *
     * @param params       the parameter contracts
     * @param methodSuffix the per-method constant suffix
     * @return the argument string fragment
     */
    private static String buildParamFqnArgs(List<EffectiveParamContract> params, String methodSuffix) {
        if (params.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < params.size(); i++) {
            sb.append(", P").append(i).append(methodSuffix);
        }
        return sb.toString();
    }

    /**
     * Builds the {@link CodeBlock} for a single {@code new ParamMeta(...)} constructor call
     * with all fields populated.
     *
     * <p>For {@link dev.vertique.codegen.jaxrs.JaxRsParamSource#BODY} parameters whose
     * {@link EffectiveParamContract#genericType()} is a parameterized type (e.g. {@code List<Foo>}),
     * the {@code genericType} slot is emitted as an anonymous Jackson
     * {@code TypeReference<List<Foo>>} subclass literal:
     * {@code new com.fasterxml.jackson.core.type.TypeReference<List<Foo>>() {}.getType()}.
     * Jackson is already on the runtime classpath for body deserialization, so this adds no new
     * dependency. Scalar body types (non-parameterized) emit {@code null} — the runtime uses
     * {@code param.type()} for those.
     *
     * @param pc          the param contract
     * @param pConstName          the string FQN constant identifier for this param's type
     * @param methodVar           the local variable name holding the resolved
     *                            {@link java.lang.reflect.Method}
     * @param paramIndex          zero-based parameter index
     * @param addComma            whether to add a trailing comma after this entry
     * @param descriptorClass     the enclosing descriptor's {@link ClassName}, used to derive this
     *                            parameter's standalone metadata-impl class name
     * @param methodIdx           zero-based method index (disambiguates the metadata-impl name
     *                            across overloaded/multiple methods on the same resource)
     * @param emittedLiteralFqns  the per-round shared dedup set of {@code <Ann>$Literal} FQNs already
     *                            written to the {@code Filer}
     * @return the code block
     */
    private CodeBlock buildParamMeta(
            EffectiveParamContract pc,
            String pConstName,
            String methodVar,
            int paramIndex,
            boolean addComma,
            ClassName descriptorClass,
            int methodIdx,
            ExecutableElement concreteMethod,
            Set<String> emittedLiteralFqns) {
        CodeBlock.Builder cb = CodeBlock.builder();

        // name (preserve null vs empty-string parity per CG-009 blank-name parity)
        if (pc.name() == null) {
            cb.add(
                    "new $T(null, $T.$L, resolveClass(support, cl_, $L), ",
                    PARAM_META,
                    PARAM_SOURCE,
                    runtimeParamSourceName(pc.source()),
                    pConstName);
        } else {
            cb.add(
                    "new $T($S, $T.$L, resolveClass(support, cl_, $L), ",
                    PARAM_META,
                    pc.name(),
                    PARAM_SOURCE,
                    runtimeParamSourceName(pc.source()),
                    pConstName);
        }

        // componentType
        if (pc.componentType() != null) {
            String componentFqn = erasedFqn(pc.componentType());
            cb.add("resolveClass(support, cl_, $S), ", componentFqn);
        } else {
            cb.add("null, ");
        }

        // genericType — emit TypeReference token for parameterized BODY types; null otherwise.
        // Using Jackson's TypeReference because it is already on the runtime classpath for body
        // deserialization and captures the full parameterized Type without reflection.
        CodeBlock genericTypeExpr = buildGenericTypeExpr(pc);
        cb.add("$L, ", genericTypeExpr);

        // defaultValue
        if (pc.defaultValue() != null) {
            cb.add("$S, ", pc.defaultValue());
        } else {
            cb.add("null, ");
        }

        // parameterMetadata — composed ParameterMetadata view, literal-backed with a reflective
        // fallback for unsupported annotation shapes (GitHub issue #162, ADR-0146 parity-first
        // policy): replaces the earlier live support.effectiveParameterAnnotations(method, index)
        // read. A registered ParamConverterProvider that inspects parameter annotations sees
        // byte-for-byte the same annotations on this path as on the reflective scan path.
        ClassName paramMetaClass = ClassName.get(
                descriptorClass.packageName(),
                descriptorClass.simpleName() + "_M" + methodIdx + "P" + paramIndex + "Meta");
        CodeBlock parameterMetadataExpr = parameterAnnotationMaterializer.materialize(
                pc, concreteMethod, paramIndex, paramMetaClass, emittedLiteralFqns, this::writeQuietly);
        cb.add("$L)", parameterMetadataExpr);
        if (addComma) {
            cb.add(",\n");
        }
        return cb.build();
    }

    /**
     * Writes a generated {@link JavaFile} to the {@code Filer}, routing any {@link IOException} to a
     * compile-time diagnostic rather than propagating it (mirrors {@code AopProxyEmitter.write}).
     *
     * @param file the file to write
     */
    private void writeQuietly(JavaFile file) {
        try {
            file.writeTo(ctx.filer());
        } catch (IOException e) {
            ctx.diagnostics()
                    .error(null, "Failed to write %s: %s", file.typeSpec().name(), e.getMessage());
        }
    }

    /**
     * Builds the {@code genericType} expression for a parameter's {@code ParamMeta} constructor
     * argument.
     *
     * <p>For {@link dev.vertique.codegen.jaxrs.JaxRsParamSource#BODY} parameters with a
     * parameterized type, emits:
     * <pre>{@code
     *   new com.fasterxml.jackson.core.type.TypeReference<List<Foo>>() {}.getType()
     * }</pre>
     * For non-body or non-parameterized types, emits {@code null}.
     *
     * @param pc the parameter contract
     * @return the code block expression
     */
    private CodeBlock buildGenericTypeExpr(EffectiveParamContract pc) {
        if (pc.genericType() == null || pc.source() != dev.vertique.codegen.jaxrs.JaxRsParamSource.BODY) {
            return CodeBlock.of("null");
        }
        // Only emit a TypeReference for parameterized types (DeclaredType with type args)
        if (!(pc.genericType() instanceof javax.lang.model.type.DeclaredType dt)
                || dt.getTypeArguments().isEmpty()) {
            return CodeBlock.of("null");
        }
        // Emit: new com.fasterxml.jackson.core.type.TypeReference<RawType<Args...>>() {}.getType()
        // We use the source-form type name (not binary) for the TypeReference generic argument.
        String sourceTypeName = sourceTypeName(pc.genericType());
        return CodeBlock.of("new com.fasterxml.jackson.core.type.TypeReference<$L>() {}.getType()", sourceTypeName);
    }

    /**
     * Returns the source-form type name for use in a TypeReference generic argument. For a
     * parameterized type like {@code List<Foo>}, returns {@code "java.util.List<com.example.Foo>"}.
     * For a raw type, returns the erased FQN.
     *
     * @param type the type mirror to render
     * @return the source-form type name string
     */
    private String sourceTypeName(TypeMirror type) {
        if (!(type instanceof javax.lang.model.type.DeclaredType dt)) {
            return erasedFqn(type);
        }
        var element = ctx.types().asElement(ctx.types().erasure(type));
        String rawName =
                element instanceof TypeElement te ? te.getQualifiedName().toString() : erasedFqn(type);
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

    /**
     * Builds the code block for a single {@code new ResourceMethodMeta(...)} invocation.
     *
     * <p>When {@code planClassName} is non-null, the full 17-argument constructor is used and a
     * {@code new XXX_ExecutionPlan()} instance is passed as the last argument. When
     * {@code planClassName} is {@code null}, {@code null} is passed instead (reflective path).
     *
     * @param methodVar          local variable name for the resolved {@link Method}
     * @param operationId        the operation ID string
     * @param httpVerb           the HTTP verb string (e.g. {@code "GET"})
     * @param fullPath           the full JAX-RS path
     * @param paramListVar       local variable name for the param meta list
     * @param returnTypeVar      local variable name for the resolved return type class
     * @param returnsFuture      whether the method returns a {@code Future}
     * @param returnsVoid        whether the method returns void
     * @param spConstName        the SecurityPolicy static constant name
     * @param mc                 the method contract (for consumes/produces)
     * @param ccVar              local variable name for the canonicalizer chain
     * @param scVar              local variable name for the sanitizer chain
     * @param methodAnnosVar     local variable name for the method annotations list
     * @param classAnnosVar      local variable name for the class annotations list
     * @param vgVar              local variable name for validation groups array
     * @param planClassName      the generated execution plan class name, or {@code null} when no
     *                           plan was emitted for this method (reflective path)
     * @return the code block for the ResourceMethodMeta
     */
    private CodeBlock buildResourceMethodMeta(
            String methodVar,
            String operationId,
            String httpVerb,
            String fullPath,
            String paramListVar,
            String returnTypeVar,
            boolean returnsFuture,
            boolean returnsVoid,
            String spConstName,
            EffectiveMethodContract mc,
            String ccVar,
            String scVar,
            String methodAnnosVar,
            String classAnnosVar,
            String vgVar,
            @Nullable ClassName planClassName) {
        CodeBlock.Builder cb = CodeBlock.builder();
        cb.add("new $T(\n", RESOURCE_METHOD_META);
        cb.indent();
        cb.add("resource,\n");
        cb.add("$L,\n", methodVar);
        cb.add("$S,\n", operationId);
        cb.add("$S,\n", httpVerb);
        cb.add("$S,\n", fullPath);
        cb.add("$L,\n", paramListVar);
        cb.add("($T<?>) $L,\n", Class.class, returnTypeVar);
        cb.add("$L,\n", returnsFuture);
        cb.add("$L,\n", returnsVoid);
        cb.add("$L,\n", spConstName);
        cb.add(buildMediaTypes(mc));
        cb.add(",\n");
        cb.add("$L,\n", vgVar);
        cb.add("$L,\n", methodAnnosVar);
        cb.add("$L,\n", classAnnosVar);
        cb.add("$L,\n", ccVar);
        cb.add("$L,\n", scVar);
        // executionPlan — non-null when a plan was emitted for this method
        if (planClassName != null) {
            cb.add("new $T()\n", planClassName);
        } else {
            cb.add("null\n");
        }
        cb.unindent();
        cb.add(")");
        return cb.build();
    }

    /**
     * Builds the {@code new ResourceMethodMeta.MediaTypes(List.of(...), List.of(...))} code block.
     *
     * @param mc the method contract
     * @return the code block
     */
    private CodeBlock buildMediaTypes(EffectiveMethodContract mc) {
        CodeBlock.Builder cb = CodeBlock.builder();
        cb.add("new $T(", MEDIA_TYPES);
        cb.add(buildStringList(mc.consumes()));
        cb.add(", ");
        cb.add(buildStringList(mc.produces()));
        cb.add(")");
        return cb.build();
    }

    /**
     * Builds the {@code describe(...)} method.
     *
     * @param resourceClass the resource class name
     * @param body          the method body code block
     * @return the method spec
     */
    private MethodSpec buildDescribeMethod(ClassName resourceClass, CodeBlock body) {
        ParameterizedTypeName listOfViolation = ParameterizedTypeName.get(LIST, SECURITY_POLICY_VIOLATION);
        ParameterizedTypeName listOfMeta = ParameterizedTypeName.get(LIST, RESOURCE_METHOD_META);

        return MethodSpec.methodBuilder("describe")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(listOfMeta)
                .addParameter(resourceClass, "resource")
                .addParameter(SUPPORT, "support")
                .addParameter(listOfViolation, "violations")
                .addCode(body)
                .build();
    }

    // --- Private: static helper methods (emitted into the generated class) ---

    /**
     * Builds the {@code resolveMethod} private static helper used in the {@code describe()} body.
     *
     * <p>Accepts an explicit {@code ClassLoader cl} to resolve parameter types from the same
     * classloader hierarchy as the resource. The support's {@code resolveMethod} uses
     * {@code resourceType.getClassLoader()} internally, but we additionally pass {@code cl} to
     * pre-resolve each param type FQN via the matching helper, which avoids a second
     * {@code forName} round-trip for the param types.
     *
     * @return the method spec
     */
    private MethodSpec buildResolveMethodHelper() {
        ParameterizedTypeName classOfQ = ParameterizedTypeName.get(
                ClassName.get(Class.class), com.palantir.javapoet.WildcardTypeName.subtypeOf(Object.class));
        return MethodSpec.methodBuilder("resolveMethod")
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(METHOD)
                .addParameter(SUPPORT, "support")
                .addParameter(ClassLoader.class, "cl")
                .addParameter(classOfQ, "resourceType")
                .addParameter(String.class, "name")
                .varargs(true)
                .addParameter(ArrayTypeName.of(String.class), "paramTypeFqns")
                .beginControlFlow("try")
                .addStatement("return support.resolveMethod(resourceType, name, paramTypeFqns)")
                .nextControlFlow("catch ($T | $T e)", ClassNotFoundException.class, NoSuchMethodException.class)
                .addStatement("throw new $T(e)", IllegalStateException.class)
                .endControlFlow()
                .build();
    }

    /**
     * Builds the {@code resolveClass} private static helper.
     *
     * <p>Takes an explicit {@code ClassLoader} parameter so the caller can pass
     * {@code resource.getClass().getClassLoader()} from within {@code describe()}, ensuring
     * user-author types are loaded from the same classloader hierarchy as the resource.
     *
     * @return the method spec
     */
    private MethodSpec buildResolveClassHelper() {
        ParameterizedTypeName classOfQ = ParameterizedTypeName.get(
                ClassName.get(Class.class), com.palantir.javapoet.WildcardTypeName.subtypeOf(Object.class));
        return MethodSpec.methodBuilder("resolveClass")
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(classOfQ)
                .addParameter(SUPPORT, "support")
                .addParameter(ClassLoader.class, "cl")
                .addParameter(String.class, "fqn")
                .beginControlFlow("try")
                .addStatement("return support.resolveClass(fqn, cl)")
                .nextControlFlow("catch ($T e)", ClassNotFoundException.class)
                .addStatement("throw new $T(e)", IllegalStateException.class)
                .endControlFlow()
                .build();
    }

    /**
     * Builds the {@code resolveClasses} private static helper.
     *
     * @return the method spec
     */
    private MethodSpec buildResolveClassesHelper() {
        ParameterizedTypeName classOfQ = ParameterizedTypeName.get(
                ClassName.get(Class.class), com.palantir.javapoet.WildcardTypeName.subtypeOf(Object.class));
        return MethodSpec.methodBuilder("resolveClasses")
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(ArrayTypeName.of(classOfQ))
                .addParameter(SUPPORT, "support")
                .addParameter(ClassLoader.class, "cl")
                .addParameter(ArrayTypeName.of(String.class), "fqns")
                .beginControlFlow("try")
                .addStatement("return support.resolveClasses(fqns, cl)")
                .nextControlFlow("catch ($T e)", ClassNotFoundException.class)
                .addStatement("throw new $T(e)", IllegalStateException.class)
                .endControlFlow()
                .build();
    }

    /**
     * Builds the {@code resolveCanonicalizerChain} private static helper.
     *
     * @return the method spec
     */
    private MethodSpec buildResolveCanonicalizerChainHelper() {
        ClassName canonicalizer = ClassName.get("dev.vertique.core.sanitization", "Canonicalizer");
        ParameterizedTypeName classOfCanonicalizerQ = ParameterizedTypeName.get(
                ClassName.get(Class.class), com.palantir.javapoet.WildcardTypeName.subtypeOf(canonicalizer));
        ParameterizedTypeName listOfCanonicalizerQ = ParameterizedTypeName.get(LIST, classOfCanonicalizerQ);
        return MethodSpec.methodBuilder("resolveCanonicalizerChain")
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(listOfCanonicalizerQ)
                .addParameter(SUPPORT, "support")
                .addParameter(ClassLoader.class, "cl")
                .addParameter(ArrayTypeName.of(String.class), "fqns")
                .beginControlFlow("try")
                .addStatement("return support.resolveCanonicalizers(fqns, cl)")
                .nextControlFlow("catch ($T e)", ClassNotFoundException.class)
                .addStatement("throw new $T(e)", IllegalStateException.class)
                .endControlFlow()
                .build();
    }

    /**
     * Builds the {@code resolveSanitizerChain} private static helper.
     *
     * @return the method spec
     */
    private MethodSpec buildResolveSanitizerChainHelper() {
        ClassName sanitizer = ClassName.get("dev.vertique.core.sanitization", "Sanitizer");
        ParameterizedTypeName classOfSanitizerQ = ParameterizedTypeName.get(
                ClassName.get(Class.class), com.palantir.javapoet.WildcardTypeName.subtypeOf(sanitizer));
        ParameterizedTypeName listOfSanitizerQ = ParameterizedTypeName.get(LIST, classOfSanitizerQ);
        return MethodSpec.methodBuilder("resolveSanitizerChain")
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(listOfSanitizerQ)
                .addParameter(SUPPORT, "support")
                .addParameter(ClassLoader.class, "cl")
                .addParameter(ArrayTypeName.of(String.class), "fqns")
                .beginControlFlow("try")
                .addStatement("return support.resolveSanitizers(fqns, cl)")
                .nextControlFlow("catch ($T e)", ClassNotFoundException.class)
                .addStatement("throw new $T(e)", IllegalStateException.class)
                .endControlFlow()
                .build();
    }

    // --- Private: type / path helpers ---

    /**
     * Returns the erased binary FQN of {@code type}. Delegates to {@link TypeMirrorFqn}
     * so the three CG-010 emitters share one source of truth on nested-type form.
     */
    private String erasedFqn(TypeMirror type) {
        return TypeMirrorFqn.erasedFqn(type, ctx);
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

    /**
     * Returns {@code true} if the type mirror is {@code io.vertx.core.Future<?>}.
     *
     * @param type the raw return type mirror
     * @return {@code true} if it is a Future
     */
    private boolean isFutureType(TypeMirror type) {
        if (type == null) {
            return false;
        }
        TypeMirror erased = ctx.types().erasure(type);
        var element = ctx.types().asElement(erased);
        if (element instanceof TypeElement te) {
            return "io.vertx.core.Future".equals(te.getQualifiedName().toString());
        }
        return false;
    }

    /**
     * Derives the full JAX-RS path by combining the class-level and method-level paths.
     *
     * <p>Joins the two path segments with a single {@code /} separator, avoiding double slashes.
     * If {@code sub} already starts with {@code /}, it is appended directly after stripping
     * any trailing {@code /} from {@code base}.
     *
     * @param classPath  the class-level path value, or {@code null}
     * @param methodPath the method-level path value, or {@code null}
     * @return the combined path, never {@code null}
     */
    private static String buildFullPath(String classPath, String methodPath) {
        String base = classPath != null ? classPath : "";
        String sub = methodPath != null ? methodPath : "";
        if (sub.isEmpty()) {
            return base.isEmpty() ? "/" : base;
        }
        if (base.isEmpty()) {
            return sub.startsWith("/") ? sub : "/" + sub;
        }
        // Strip trailing slash from base to prevent double-slash when sub starts with /
        String normalizedBase = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        // Ensure sub starts with /
        String normalizedSub = sub.startsWith("/") ? sub : "/" + sub;
        String joined = normalizedBase + normalizedSub;
        if (!joined.startsWith("/")) {
            joined = "/" + joined;
        }
        return joined;
    }

    /**
     * Extracts the short HTTP verb (e.g. "GET") from the FQN or short annotation name.
     *
     * <p>The runtime expects the verb as an uppercase string like "GET", "POST". The effective
     * contract stores the annotation FQN (e.g. "jakarta.ws.rs.GET") or the short name.
     *
     * @param httpMethodFqn the HTTP method annotation FQN or short name; may be {@code null}
     * @return the short uppercase verb
     */
    private static String shortHttpVerb(String httpMethodFqn) {
        if (httpMethodFqn == null) {
            return null;
        }
        int dot = httpMethodFqn.lastIndexOf('.');
        return dot >= 0 ? httpMethodFqn.substring(dot + 1) : httpMethodFqn;
    }

    /**
     * Maps a compile-time {@link JaxRsParamSource} to the runtime
     * {@code ResourceMethodMeta.ParamSource} enum constant name.
     *
     * <p>The enum values have identical names, so this is a direct name-lookup.
     *
     * @param source the compile-time source classification
     * @return the matching runtime enum constant name
     */
    private static String runtimeParamSourceName(JaxRsParamSource source) {
        return source.name();
    }
}
