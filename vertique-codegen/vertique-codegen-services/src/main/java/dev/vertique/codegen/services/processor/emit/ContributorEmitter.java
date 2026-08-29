// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.services.processor.emit;

import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeSpec;
import dev.vertique.codegen.AnnotationMirrors;
import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.Conditions;
import dev.vertique.codegen.services.processor.scan.ContractModel;
import dev.vertique.codegen.services.processor.scan.ImplCandidate.ImplKind;
import dev.vertique.codegen.services.processor.scan.OperationModel;
import dev.vertique.codegen.services.processor.scan.ParamModel;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.processing.Generated;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;

/**
 * Emits a single {@code {Contract}_ContractContributor} class per contract group (one or more
 * validated {@link ContractModel}s for the same contract type).
 *
 * <p>CG-011 W4 changes the emission model from per-impl to per-group:
 * <ul>
 *   <li>Each contract group produces exactly one {@code _ContractContributor}.</li>
 *   <li>The contributor's {@code @Inject} constructor accepts one {@code Provider<Impl>} per
 *       candidate in the group, enabling lazy instantiation (NFR-CG011-002).</li>
 *   <li>The {@code contribute(JsonObject)} method applies the <em>size-aware contract</em>:
 *     <ul>
 *       <li><strong>Single-impl, unconditional</strong>: always returns the entry.</li>
 *       <li><strong>Single-impl, conditional</strong>: returns the entry if the condition is met;
 *           returns {@code List.of()} (NOT throw) if not (FR-CG011-011).</li>
 *       <li><strong>Multi-impl</strong>: walks all conditional candidates; if exactly one matches,
 *           returns it. If none matches and an unconditional default exists, returns the default.
 *           If none matches and no default exists, throws {@link ServiceRegistrationException}
 *           (FR-CG011-013). If more than one matches, throws (FR-CG011-013).</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <p>Per-candidate metadata fields follow the naming pattern
 * {@code <IMPLPREFIX>_<OPNAME>_METHOD}, {@code <IMPLPREFIX>_<OPNAME>_RESILIENCE},
 * {@code <IMPLPREFIX>_<OPNAME>_METHOD_ANNOTATIONS}, and (for handler pattern)
 * {@code <IMPLPREFIX>_<OPNAME>_HANDLER_METHOD}. {@code <IMPLPREFIX>} is derived from the impl's
 * unique-key (see below) via {@code Identifiers.constantName} so {@code UserServiceHandler}
 * yields {@code USER_SERVICE_HANDLER}. Per-contract fields like {@code CONTRACT_CLASS_ANNOTATIONS}
 * are shared across all candidates in the group.
 *
 * <p><strong>Cross-package simple-name disambiguation.</strong> When a multi-impl group contains
 * two or more impls with the same simple class name across different packages (e.g.,
 * {@code a.UserServiceSandbox} and {@code b.UserServiceSandbox}), each colliding impl's unique
 * key is prefixed with its package path (dots replaced by underscores) so all derived identifiers
 * (condition constants, metadata fields, provider fields, {@code buildEntry_*} helpers) remain
 * unique within the generated class. Groups without simple-name collisions use the simple name
 * directly, so the common single-impl/non-colliding case retains the readable naming.
 *
 * <p>Generated unconditional candidates use a shared {@code EMPTY_CONDITIONS} constant.
 * Conditional candidates emit their own named {@code PropertyCondition[]} constant.
 *
 * <p>Example shape (multi-impl, default + one conditional):
 * <pre>{@code
 * @Generated("dev.vertique.codegen.services.processor.ServiceContractProcessor")
 * @Singleton
 * public final class UserService_ContractContributor implements ServiceContractContributor {
 *
 *     // --- Per-candidate condition constants ---
 *     private static final PropertyCondition[] EMPTY_CONDITIONS = new PropertyCondition[0];
 *     private static final PropertyCondition[] USER_SERVICE_SANDBOX_CONDITIONS =
 *             new PropertyCondition[] {
 *         new PropertyCondition("sandboxEnabled", "true", false)
 *     };
 *
 *     // --- Shared contract-class annotations ---
 *     private static final List<Annotation> CONTRACT_CLASS_ANNOTATIONS = ...;
 *
 *     // --- Per-candidate metadata fields ---
 *     private static final Method USER_SERVICE_HANDLER_GETUSER_METHOD = ...;
 *     ...
 *
 *     // --- Providers (lazy instantiation) ---
 *     private final Provider<UserServiceHandler> defaultProvider;
 *     private final Provider<UserServiceSandbox> sandboxProvider;
 *
 *     @Inject
 *     public UserService_ContractContributor(
 *             Provider<UserServiceHandler> defaultProvider,
 *             Provider<UserServiceSandbox> sandboxProvider) { ... }
 *
 *     @Override
 *     public List<ContractEntry<?>> contribute(JsonObject config) {
 *         // Multi-impl: collect conditional matches
 *         List<Object> matches = new ArrayList<>();
 *         if (PropertyCondition.matchesAll(config, USER_SERVICE_SANDBOX_CONDITIONS)) {
 *             matches.add(sandboxProvider);
 *         }
 *         if (matches.size() > 1) { throw new ServiceRegistrationException(...); }
 *         if (matches.size() == 1) {
 *             Object p = matches.get(0);
 *             return buildEntry_UserServiceSandbox(...);
 *         }
 *         // No conditional match — fall back to unconditional default
 *         return buildEntry_UserServiceHandler(defaultProvider.get(), config);
 *     }
 * }
 * }</pre>
 */
public final class ContributorEmitter {

    // --- Well-known type names ---
    private static final ClassName SERVICE_CONTRACT_CONTRIBUTOR =
            ClassName.get("dev.vertique.services", "ServiceContractContributor");
    private static final ClassName SERVICE_CONTRACT_ENTRIES =
            ClassName.get("dev.vertique.services", "ServiceContractEntries");
    private static final ClassName CONTRACT_ENTRY =
            ClassName.get("dev.vertique.services.ServiceContractRegistry", "ContractEntry");
    private static final ClassName JSON_OBJECT = ClassName.get("io.vertx.core.json", "JsonObject");
    private static final ClassName PARAM_SOURCE =
            ClassName.get("dev.vertique.services.dispatch.ServiceMethodMeta", "ParamSource");
    private static final ClassName RESILIENCE_ANNOTATIONS =
            ClassName.get("dev.vertique.resilience.annotation", "ResilienceAnnotations");
    private static final ClassName ANNOTATION_RESOLVER = ClassName.get("dev.vertique.core.util", "AnnotationResolver");
    private static final ClassName INJECT = ClassName.get("jakarta.inject", "Inject");
    private static final ClassName PROVIDER = ClassName.get("jakarta.inject", "Provider");
    private static final ClassName SINGLETON = ClassName.get("jakarta.inject", "Singleton");
    private static final ClassName LIST = ClassName.get("java.util", "List");
    private static final ClassName ARRAY_LIST = ClassName.get("java.util", "ArrayList");
    private static final ClassName ANNOTATION = ClassName.get("java.lang.annotation", "Annotation");
    private static final ClassName METHOD = ClassName.get("java.lang.reflect", "Method");
    private static final ClassName SERVICE_REGISTRATION_EXCEPTION =
            ClassName.get("dev.vertique.services", "ServiceRegistrationException");
    private static final ClassName SERVICE_REGISTRATION_VIOLATION =
            ClassName.get("dev.vertique.services", "ServiceRegistrationViolation");

    private static final String PROCESSOR_FQN = "dev.vertique.codegen.services.processor.ServiceContractProcessor";
    private static final String EMPTY_CONDITIONS_CONST = "EMPTY_CONDITIONS";

    private final CodegenContext ctx;
    private final Conditions conditions;

    /**
     * Constructs an emitter bound to the given codegen context.
     *
     * @param ctx the shared codegen context; must not be {@code null}
     */
    public ContributorEmitter(CodegenContext ctx) {
        this.ctx = ctx;
        this.conditions = new Conditions(ctx.annotations());
    }

    /**
     * Emits one {@code {Contract}_ContractContributor} class for the given contract group.
     *
     * <p>All candidates in the group must share the same {@code contractType}. The group is
     * expected to have passed all group-level validators before this method is called.
     *
     * @param group the list of validated contract models for a single contract type;
     *              must not be {@code null} or empty
     */
    public void emit(List<ContractModel> group) {
        if (group.isEmpty()) {
            return;
        }

        ContractModel representative = group.get(0);
        TypeElement contractType = representative.contractType();

        // Disambiguate identifiers when two impls in the same group share a simple class name
        // across different packages (e.g., a.UserServiceSandbox vs b.UserServiceSandbox). Without
        // this, condition constants, metadata fields, provider fields, and buildEntry_* helpers
        // would collide and the generated source would fail to compile.
        Map<TypeElement, String> implKeys = buildUniqueImplKeys(group);

        String contractPackage = ctx.packageNameOf(contractType);
        String contributorSimpleName = contractType.getSimpleName() + "_ContractContributor";

        ClassName contractClass =
                ClassName.get(contractPackage, contractType.getSimpleName().toString());

        // Read @ServiceContract annotation attributes (namespace / name)
        var contractAnno = AnnotationMirrors.findByFqn(contractType, "dev.vertique.services.ServiceContract")
                .orElse(null);
        String serviceNamespace = contractAnno != null
                ? ctx.annotations()
                        .attribute(contractAnno, "namespace", String.class)
                        .orElse("")
                : "";
        String serviceName = contractAnno != null
                ? ctx.annotations()
                        .attribute(contractAnno, "value", String.class)
                        .orElse("")
                : "";

        // --- resolveMethod helper ---
        MethodSpec resolveMethodHelper = buildResolveMethodHelper();

        ParameterizedTypeName listOfAnnotation = ParameterizedTypeName.get(LIST, ANNOTATION);

        // --- Static fields ---
        List<FieldSpec> staticFields = new ArrayList<>();

        // Shared CONTRACT_CLASS_ANNOTATIONS field (same contract for all candidates)
        staticFields.add(FieldSpec.builder(
                        listOfAnnotation,
                        "CONTRACT_CLASS_ANNOTATIONS",
                        Modifier.PRIVATE,
                        Modifier.STATIC,
                        Modifier.FINAL)
                .initializer("$T.resolveClassAnnotations($T.class)", ANNOTATION_RESOLVER, contractClass)
                .build());

        // EMPTY_CONDITIONS constant if any candidate is unconditional in a multi-impl group
        boolean hasMultipleImpls = group.size() > 1;
        boolean needsEmptyConditions =
                hasMultipleImpls && group.stream().anyMatch(m -> !Conditions.isConditional(m.implType()));

        if (needsEmptyConditions) {
            staticFields.add(FieldSpec.builder(
                            com.palantir.javapoet.ArrayTypeName.of(Conditions.PROPERTY_CONDITION),
                            EMPTY_CONDITIONS_CONST,
                            Modifier.PRIVATE,
                            Modifier.STATIC,
                            Modifier.FINAL)
                    .initializer("new $T[0]", Conditions.PROPERTY_CONDITION)
                    .build());
        }

        // Per-candidate PropertyCondition[] constants (for conditional candidates in multi-impl groups,
        // or for any candidate in single-impl groups)
        for (ContractModel model : group) {
            TypeElement implType = model.implType();
            List<Conditions.ConditionData> conditionData = conditions.read(implType);
            if (!conditionData.isEmpty()) {
                String constantName = Conditions.constantName(implKeys.get(implType));
                staticFields.add(FieldSpec.builder(
                                com.palantir.javapoet.ArrayTypeName.of(Conditions.PROPERTY_CONDITION),
                                constantName,
                                Modifier.PRIVATE,
                                Modifier.STATIC,
                                Modifier.FINAL)
                        .initializer(Conditions.arrayInitializer(conditionData))
                        .build());
            }
        }

        // Per-candidate, per-operation metadata fields
        for (ContractModel model : group) {
            TypeElement implType = model.implType();
            String implPrefix = Conditions.constantName(implKeys.get(implType));
            ClassName implClass = ClassName.get(
                    ctx.packageNameOf(implType), implType.getSimpleName().toString());

            for (OperationModel op : model.operations()) {
                // <IMPL>_<OP>_METHOD
                String methodFieldName = implMethodFieldName(implPrefix, op);
                CodeBlock.Builder methodInit = CodeBlock.builder();
                methodInit.add(
                        "resolveMethod($T.class, $S",
                        contractClass,
                        op.contractMethod().getSimpleName());
                for (ParamModel p : op.params()) {
                    methodInit.add(", $L", toClassLiteralMirror(p.type()));
                }
                methodInit.add(")");
                staticFields.add(
                        FieldSpec.builder(METHOD, methodFieldName, Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                                .initializer(methodInit.build())
                                .build());

                // <IMPL>_<OP>_METHOD_ANNOTATIONS
                String methodAnnotationsFieldName = implMethodAnnotationsFieldName(implPrefix, op);
                staticFields.add(FieldSpec.builder(
                                listOfAnnotation,
                                methodAnnotationsFieldName,
                                Modifier.PRIVATE,
                                Modifier.STATIC,
                                Modifier.FINAL)
                        .initializer("$T.resolveMethodAnnotations($L)", ANNOTATION_RESOLVER, methodFieldName)
                        .build());

                // <IMPL>_<OP>_RESILIENCE
                String resilienceFieldName = implResilienceFieldName(implPrefix, op);
                staticFields.add(FieldSpec.builder(
                                RESILIENCE_ANNOTATIONS,
                                resilienceFieldName,
                                Modifier.PRIVATE,
                                Modifier.STATIC,
                                Modifier.FINAL)
                        .initializer("$T.resolve($T.class, $L)", RESILIENCE_ANNOTATIONS, contractClass, methodFieldName)
                        .build());

                // <IMPL>_<OP>_HANDLER_METHOD (handler pattern only)
                if (model.kind() == ImplKind.HANDLER) {
                    String handlerMethodFieldName = implHandlerMethodFieldName(implPrefix, op);
                    CodeBlock.Builder handlerMethodInit = CodeBlock.builder();
                    handlerMethodInit.add(
                            "resolveMethod($T.class, $S",
                            implClass,
                            op.handlerMethod().getSimpleName());
                    for (ParamModel p : op.handlerParams()) {
                        handlerMethodInit.add(", $L", toClassLiteralMirror(p.type()));
                    }
                    handlerMethodInit.add(")");
                    staticFields.add(FieldSpec.builder(
                                    METHOD, handlerMethodFieldName, Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                            .initializer(handlerMethodInit.build())
                            .build());
                }
            }
        }

        // --- Per-candidate provider fields + constructor parameters ---
        List<FieldSpec> providerFields = new ArrayList<>();
        MethodSpec.Builder constructorBuilder = MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(AnnotationSpec.builder(INJECT).build());

        for (ContractModel model : group) {
            TypeElement implType = model.implType();
            ClassName implClass = ClassName.get(
                    ctx.packageNameOf(implType), implType.getSimpleName().toString());
            String providerFieldName = providerFieldName(implKeys.get(implType));
            ParameterizedTypeName providerType = ParameterizedTypeName.get(PROVIDER, implClass);

            providerFields.add(FieldSpec.builder(providerType, providerFieldName, Modifier.PRIVATE, Modifier.FINAL)
                    .build());
            constructorBuilder.addParameter(
                    ParameterSpec.builder(providerType, providerFieldName).build());
            constructorBuilder.addStatement("this.$L = $L", providerFieldName, providerFieldName);
        }

        // --- contribute() method ---
        ParameterizedTypeName listOfContractEntry = ParameterizedTypeName.get(
                LIST,
                ParameterizedTypeName.get(
                        CONTRACT_ENTRY, com.palantir.javapoet.WildcardTypeName.subtypeOf(Object.class)));

        MethodSpec contributeMethod = buildContributeMethod(
                group,
                implKeys,
                contractClass,
                serviceNamespace,
                serviceName,
                listOfContractEntry,
                hasMultipleImpls,
                needsEmptyConditions);

        // --- Per-candidate buildEntry_<ImplSimpleName>() helper methods ---
        List<MethodSpec> buildEntryMethods = new ArrayList<>();
        for (ContractModel model : group) {
            buildEntryMethods.add(buildEntryHelper(
                    model,
                    implKeys.get(model.implType()),
                    contractClass,
                    serviceNamespace,
                    serviceName,
                    listOfContractEntry));
        }

        // --- Assemble the class ---
        TypeSpec.Builder classBuilder = TypeSpec.classBuilder(contributorSimpleName)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addAnnotation(AnnotationSpec.builder(ClassName.get(Generated.class))
                        .addMember("value", "$S", PROCESSOR_FQN)
                        .build())
                .addAnnotation(AnnotationSpec.builder(SINGLETON).build())
                .addSuperinterface(SERVICE_CONTRACT_CONTRIBUTOR);

        for (FieldSpec sf : staticFields) {
            classBuilder.addField(sf);
        }
        for (FieldSpec pf : providerFields) {
            classBuilder.addField(pf);
        }
        classBuilder.addMethod(constructorBuilder.build());
        classBuilder.addMethod(contributeMethod);
        for (MethodSpec bm : buildEntryMethods) {
            classBuilder.addMethod(bm);
        }
        classBuilder.addMethod(resolveMethodHelper);

        TypeSpec contributorSpec = classBuilder.build();
        JavaFile javaFile = JavaFile.builder(contractPackage, contributorSpec).build();

        try {
            javaFile.writeTo(ctx.filer());
        } catch (IOException e) {
            ctx.diagnostics().error(contractType, "Failed to write %s: %s", contributorSimpleName, e.getMessage());
        }
    }

    // --- Internal helpers ---

    /**
     * Builds the {@code contribute(JsonObject)} method implementing the size-aware selection contract.
     *
     * <p>For single-impl groups the generated body is compact (direct check or direct return).
     * For multi-impl groups, conditional candidates are walked in declaration order, and the
     * unconditional default (if present) is used as a fallback when no conditional matches.
     *
     * @param group               the contract group
     * @param implKeys            disambiguated impl identifier keys (from {@code buildUniqueImplKeys})
     * @param contractClass       the contract type ClassName
     * @param serviceNamespace    the {@code @ServiceContract} namespace attribute value
     * @param serviceName         the {@code @ServiceContract} name attribute value
     * @param listOfContractEntry the return type
     * @param hasMultipleImpls    whether the group contains more than one candidate
     * @param needsEmptyConditions whether the EMPTY_CONDITIONS constant was emitted
     * @return the built contribute method spec
     */
    private MethodSpec buildContributeMethod(
            List<ContractModel> group,
            Map<TypeElement, String> implKeys,
            ClassName contractClass,
            String serviceNamespace,
            String serviceName,
            ParameterizedTypeName listOfContractEntry,
            boolean hasMultipleImpls,
            boolean needsEmptyConditions) {

        CodeBlock.Builder body = CodeBlock.builder();

        if (!hasMultipleImpls) {
            // --- Single-impl group ---
            ContractModel model = group.get(0);
            TypeElement implType = model.implType();
            String key = implKeys.get(implType);
            List<Conditions.ConditionData> conditionData = conditions.read(implType);
            String providerField = providerFieldName(key);
            String buildEntryMethodName = buildEntryMethodName(key);

            if (conditionData.isEmpty()) {
                // Unconditional single impl — always return the entry
                body.addStatement("return $L($L.get(), config)", buildEntryMethodName, providerField);
            } else {
                // Conditional single impl — return empty list when condition not met (FR-CG011-011)
                String conditionsConst = Conditions.constantName(key);
                body.beginControlFlow("if ($T.matchesAll(config, $L))", Conditions.PROPERTY_CONDITION, conditionsConst);
                body.addStatement("return $L($L.get(), config)", buildEntryMethodName, providerField);
                body.endControlFlow();
                body.addStatement("return $T.of()", LIST);
            }
        } else {
            // --- Multi-impl group ---
            // Identify the unconditional default (if any)
            ContractModel defaultModel = group.stream()
                    .filter(m -> !Conditions.isConditional(m.implType()))
                    .findFirst()
                    .orElse(null);
            List<ContractModel> conditionalModels = group.stream()
                    .filter(m -> Conditions.isConditional(m.implType()))
                    .toList();

            String contractName = contractClass.simpleName();

            // Collect matches from conditional candidates
            body.addStatement("$T<Object> matches = new $T<>()", LIST, ARRAY_LIST);
            body.addStatement("$T<String> matchedNames = new $T<>()", LIST, ARRAY_LIST);
            for (ContractModel cModel : conditionalModels) {
                TypeElement implType = cModel.implType();
                String key = implKeys.get(implType);
                String conditionsConst = Conditions.constantName(key);
                String providerField = providerFieldName(key);
                body.beginControlFlow("if ($T.matchesAll(config, $L))", Conditions.PROPERTY_CONDITION, conditionsConst);
                body.addStatement("matches.add($L)", providerField);
                // Diagnostic name uses the impl's full qualified name so collisions are
                // distinguishable in the error message even when simple names collide.
                body.addStatement(
                        "matchedNames.add($S)", implType.getQualifiedName().toString());
                body.endControlFlow();
            }

            // Guard: >1 matches → throw
            body.beginControlFlow("if (matches.size() > 1)");
            body.addStatement(
                    "throw new $T($T.of($T.ofType($T.class, $S + matchedNames)))",
                    SERVICE_REGISTRATION_EXCEPTION,
                    LIST,
                    SERVICE_REGISTRATION_VIOLATION,
                    contractClass,
                    "Service contract '" + contractName + "' has multiple active implementations: ");
            body.endControlFlow();

            // Exactly 1 conditional match — use it
            body.beginControlFlow("if (matches.size() == 1)");
            body.addStatement("Object matchedProvider = matches.get(0)");
            for (ContractModel cModel : conditionalModels) {
                TypeElement implType = cModel.implType();
                String key = implKeys.get(implType);
                String providerField = providerFieldName(key);
                String buildEntryMethodName = buildEntryMethodName(key);
                body.beginControlFlow("if (matchedProvider == $L)", providerField);
                body.addStatement("return $L($L.get(), config)", buildEntryMethodName, providerField);
                body.endControlFlow();
            }
            body.endControlFlow();

            // 0 matches — use default if present, otherwise throw
            if (defaultModel != null) {
                String defaultKey = implKeys.get(defaultModel.implType());
                String defaultProviderField = providerFieldName(defaultKey);
                String defaultBuildMethod = buildEntryMethodName(defaultKey);
                body.addStatement("return $L($L.get(), config)", defaultBuildMethod, defaultProviderField);
            } else {
                body.addStatement(
                        "throw new $T($T.of($T.ofType($T.class, $S)))",
                        SERVICE_REGISTRATION_EXCEPTION,
                        LIST,
                        SERVICE_REGISTRATION_VIOLATION,
                        contractClass,
                        "Service contract '"
                                + contractName
                                + "' has no active implementation"
                                + " — no condition matched and no default is registered");
            }
        }

        return MethodSpec.methodBuilder("contribute")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(listOfContractEntry)
                .addParameter(JSON_OBJECT, "config")
                .addCode(body.build())
                .build();
    }

    /**
     * Builds the private {@code buildEntry_<ImplSimpleName>(ImplType impl, JsonObject config)}
     * helper that assembles the {@link dev.vertique.services.ServiceContractRegistry.ContractEntry}
     * for the given candidate.
     *
     * <p>The helper encapsulates all per-candidate metadata fields and the full builder chain,
     * keeping the {@code contribute()} method small.
     *
     * @param model          the candidate model
     * @param contractClass  the contract type ClassName
     * @param serviceNamespace the {@code @ServiceContract} namespace attribute value
     * @param serviceName    the {@code @ServiceContract} name attribute value
     * @param returnType     the return type for the helper method
     * @return the built private method spec
     */
    private MethodSpec buildEntryHelper(
            ContractModel model,
            String implKey,
            ClassName contractClass,
            String serviceNamespace,
            String serviceName,
            ParameterizedTypeName returnType) {

        TypeElement implType = model.implType();
        ClassName implClass = ClassName.get(
                ctx.packageNameOf(implType), implType.getSimpleName().toString());
        String implPrefix = Conditions.constantName(implKey);

        CodeBlock.Builder body = CodeBlock.builder();
        body.add("return $T.of($T.deployable()\n", LIST, SERVICE_CONTRACT_ENTRIES);
        body.indent();
        body.add(".contract($T.class)\n", contractClass);
        body.add(".serviceInstance(impl)\n");
        if (!serviceNamespace.isBlank()) {
            body.add(".namespace($S)\n", serviceNamespace);
        }
        body.add(".name($S)\n", serviceName);

        for (OperationModel op : model.operations()) {
            String methodFieldName = implMethodFieldName(implPrefix, op);
            String resilienceFieldName = implResilienceFieldName(implPrefix, op);
            String methodAnnotationsFieldName = implMethodAnnotationsFieldName(implPrefix, op);

            body.add(".operation($S)\n", op.operationName());
            body.indent();
            body.add(".method($L)\n", methodFieldName);

            if (model.kind() == ImplKind.HANDLER) {
                String handlerFieldName = implHandlerMethodFieldName(implPrefix, op);
                body.add(".handlerMethod($L)\n", handlerFieldName);
            }

            body.add(".returnType($L)\n", toClassLiteralMirror(op.returnType()));

            if (op.payloadType() != null) {
                body.add(".payloadType($L)\n", toClassLiteralMirror(op.payloadType()));
            }

            for (ParamModel p : op.params()) {
                body.add(".param($S, $T.$L, $L)\n", p.name(), PARAM_SOURCE, p.source(), toClassLiteralMirror(p.type()));
            }

            if (model.kind() == ImplKind.HANDLER) {
                for (ParamModel p : op.handlerParams()) {
                    body.add(
                            ".handlerParam($S, $T.$L, $L)\n",
                            p.name(),
                            PARAM_SOURCE,
                            p.source(),
                            toClassLiteralMirror(p.type()));
                }
            }

            if (op.oneWay()) {
                body.add(".oneWay()\n");
            }

            body.add(".resilienceAnnotations($L)\n", resilienceFieldName);
            body.add(".methodAnnotations($L)\n", methodAnnotationsFieldName);
            body.add(".classAnnotations(CONTRACT_CLASS_ANNOTATIONS)\n");
            body.add(".done()\n");
            body.unindent();
        }

        // Config path mirrors ServicesConfig.fromConfig: services.contracts.{namespace}.{name},
        // where the empty default namespace is addressed by the reserved "_" sentinel key.
        String namespaceSegment = serviceNamespace.isBlank() ? "_" : serviceNamespace;
        body.add(".deploymentOptions(config, \"services\", \"contracts\", $S, $S)\n", namespaceSegment, serviceName);
        body.add(".build());\n");
        body.unindent();

        return MethodSpec.methodBuilder(buildEntryMethodName(implKey))
                .addModifiers(Modifier.PRIVATE)
                .returns(returnType)
                .addParameter(implClass, "impl")
                .addParameter(JSON_OBJECT, "config")
                .addCode(body.build())
                .build();
    }

    /**
     * Builds the {@code resolveMethod} private static helper used in static field initializers.
     *
     * @return the {@link MethodSpec} for the helper method
     */
    private static MethodSpec buildResolveMethodHelper() {
        return MethodSpec.methodBuilder("resolveMethod")
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(METHOD)
                .addParameter(
                        ParameterizedTypeName.get(
                                ClassName.get(Class.class),
                                com.palantir.javapoet.WildcardTypeName.subtypeOf(Object.class)),
                        "cls")
                .addParameter(String.class, "n")
                .varargs(true)
                .addParameter(
                        com.palantir.javapoet.ArrayTypeName.of(ParameterizedTypeName.get(
                                ClassName.get(Class.class),
                                com.palantir.javapoet.WildcardTypeName.subtypeOf(Object.class))),
                        "p")
                .beginControlFlow("try")
                .addStatement("return cls.getMethod(n, p)")
                .nextControlFlow("catch ($T e)", NoSuchMethodException.class)
                .addStatement("throw new $T(e)", IllegalStateException.class)
                .endControlFlow()
                .build();
    }

    /**
     * Builds a per-impl unique identifier-key map for a contract group. The key is used as the base
     * for derived identifiers (condition constant names, per-op metadata field prefixes, provider
     * field names, {@code buildEntry_*} helper names). The set of derived names — not just the raw
     * keys — must be globally unique within the group so the generated source file compiles.
     *
     * <p>Three escalation tiers are tried in order, returning the first whose derived identifier
     * forms are all unique within the group:
     * <ol>
     *   <li><strong>Tier 1 — simple name.</strong> The impl's simple class name. Used when no two
     *       impls in the group produce colliding derived names.</li>
     *   <li><strong>Tier 2 — package-prefixed.</strong> {@code <pkg-with-underscores>_<simpleName>}.
     *       Disambiguates the common cross-package collision (e.g. {@code a.Foo} vs {@code b.Foo}).
     *       Some edge cases still alias here — e.g. {@code a.Foo} vs {@code c.a_Foo}, or two impls
     *       whose simple names differ only in the first-character case ({@code Foo} vs {@code foo}
     *       collapse to the same {@code fooProvider} field and {@code FOO_*} constants). The next
     *       tier handles those edge cases.</li>
     *   <li><strong>Tier 3 — positional.</strong> {@code impl<n>_<simpleName>} where {@code n} is
     *       the impl's index in the group. Always unique by construction.</li>
     * </ol>
     *
     * <p>Uniqueness is checked against the actual identifier transforms applied downstream — both
     * {@link Identifiers#constantName(String)} (used for {@code _CONDITIONS} and per-op metadata
     * field prefixes) and the lower-first-char provider-field form — not just the raw key string.
     * This guards against the case-only-difference collisions that case-sensitive raw uniqueness
     * would miss.
     *
     * @param group the contract group; must not be {@code null} or empty
     * @return map from impl type element to disambiguated identifier key
     */
    private Map<TypeElement, String> buildUniqueImplKeys(List<ContractModel> group) {
        // --- Tier 1: simple name ---
        Map<TypeElement, String> keys = new LinkedHashMap<>();
        for (ContractModel m : group) {
            keys.put(m.implType(), m.implType().getSimpleName().toString());
        }
        if (areKeysUnique(keys.values())) {
            return keys;
        }

        // --- Tier 2: package-prefixed for everyone (uniform shape avoids tier-1 aliasing) ---
        keys.clear();
        for (ContractModel m : group) {
            TypeElement t = m.implType();
            String pkg = ctx.packageNameOf(t).replace('.', '_');
            String simple = t.getSimpleName().toString();
            keys.put(t, pkg.isEmpty() ? simple : pkg + "_" + simple);
        }
        if (areKeysUnique(keys.values())) {
            return keys;
        }

        // --- Tier 3: positional fallback (guaranteed unique) ---
        keys.clear();
        for (int i = 0; i < group.size(); i++) {
            TypeElement t = group.get(i).implType();
            keys.put(t, "impl" + i + "_" + t.getSimpleName().toString());
        }
        return keys;
    }

    /**
     * Returns {@code true} when the keys are pairwise distinct AND all downstream identifier
     * transforms (provider field name, {@code SCREAMING_SNAKE_CASE} constant name) also produce
     * pairwise-distinct values.
     */
    private static boolean areKeysUnique(java.util.Collection<String> keys) {
        if (new java.util.HashSet<>(keys).size() != keys.size()) {
            return false;
        }
        java.util.Set<String> providerNames = new java.util.HashSet<>();
        for (String key : keys) {
            if (!providerNames.add(providerFieldName(key))) {
                return false;
            }
        }
        java.util.Set<String> constantNames = new java.util.HashSet<>();
        for (String key : keys) {
            if (!constantNames.add(dev.vertique.codegen.support.Identifiers.constantName(key))) {
                return false;
            }
        }
        return true;
    }

    // --- Field / method name derivation helpers ---

    /**
     * Returns the provider field name for an impl: decapitalized unique key + "Provider".
     *
     * <p>Example: {@code "UserServiceHandler"} → {@code "userServiceHandlerProvider"}.
     *
     * @param implKey the disambiguated impl identifier key (from {@code buildUniqueImplKeys})
     * @return a valid Java identifier
     */
    private static String providerFieldName(String implKey) {
        return Character.toLowerCase(implKey.charAt(0)) + implKey.substring(1) + "Provider";
    }

    /**
     * Returns the private helper method name for building a {@code ContractEntry} for an impl.
     *
     * <p>Example: {@code "UserServiceHandler"} → {@code "buildEntry_UserServiceHandler"}.
     *
     * @param implKey the disambiguated impl identifier key (from {@code buildUniqueImplKeys})
     * @return a valid Java identifier
     */
    private static String buildEntryMethodName(String implKey) {
        return "buildEntry_" + implKey;
    }

    /**
     * Returns the static field name for the contract-side {@link java.lang.reflect.Method} of an
     * operation, scoped to a specific impl.
     *
     * <p>Pattern: {@code <IMPLPREFIX>_<CONTRACTMETHODNAME>_METHOD}.
     *
     * @param implPrefix the impl simple name in upper-case
     * @param op         the operation model
     * @return a valid Java identifier
     */
    private static String implMethodFieldName(String implPrefix, OperationModel op) {
        return implPrefix + "_" + op.contractMethod().getSimpleName().toString().toUpperCase() + "_METHOD";
    }

    /**
     * Returns the static field name for the method-annotation {@link List} of an operation,
     * scoped to a specific impl.
     *
     * <p>Pattern: {@code <IMPLPREFIX>_<CONTRACTMETHODNAME>_METHOD_ANNOTATIONS}.
     *
     * @param implPrefix the impl simple name in upper-case
     * @param op         the operation model
     * @return a valid Java identifier
     */
    private static String implMethodAnnotationsFieldName(String implPrefix, OperationModel op) {
        return implPrefix + "_" + op.contractMethod().getSimpleName().toString().toUpperCase() + "_METHOD_ANNOTATIONS";
    }

    /**
     * Returns the static field name for the {@link dev.vertique.resilience.annotation.ResilienceAnnotations}
     * of an operation, scoped to a specific impl.
     *
     * <p>Pattern: {@code <IMPLPREFIX>_<CONTRACTMETHODNAME>_RESILIENCE}.
     *
     * @param implPrefix the impl simple name in upper-case
     * @param op         the operation model
     * @return a valid Java identifier
     */
    private static String implResilienceFieldName(String implPrefix, OperationModel op) {
        return implPrefix + "_" + op.contractMethod().getSimpleName().toString().toUpperCase() + "_RESILIENCE";
    }

    /**
     * Returns the static field name for the handler-side {@link java.lang.reflect.Method} of an
     * operation, scoped to a specific impl (handler pattern only).
     *
     * <p>Pattern: {@code <IMPLPREFIX>_<HANDLERMETHODNAME>_HANDLER_METHOD}.
     *
     * @param implPrefix the impl simple name in upper-case
     * @param op         the operation model
     * @return a valid Java identifier
     */
    private static String implHandlerMethodFieldName(String implPrefix, OperationModel op) {
        return implPrefix + "_" + op.handlerMethod().getSimpleName().toString().toUpperCase() + "_HANDLER_METHOD";
    }

    /**
     * Converts a {@link TypeMirror} to a {@code ClassName.class} literal expression for JavaPoet
     * code blocks. For parameterized types, the raw type is used.
     *
     * @param type the type mirror to convert
     * @return a code-literal string like {@code "java.lang.String.class"}
     */
    private String toClassLiteralMirror(TypeMirror type) {
        TypeMirror erased = ctx.types().erasure(type);
        return erased.toString() + ".class";
    }
}
