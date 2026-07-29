// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.services.processor.emit;

import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import com.palantir.javapoet.WildcardTypeName;
import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.services.processor.ServiceAnnotations;
import dev.vertique.codegen.services.processor.scan.ClientContractModel;
import dev.vertique.codegen.services.processor.scan.OperationModel;
import dev.vertique.codegen.services.processor.scan.ParamModel;
import dev.vertique.codegen.support.Identifiers;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import javax.annotation.processing.Generated;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;

/**
 * Emits one {@code {Contract}_ServiceClientProxy} static client proxy per source-root
 * {@code @ServiceContract} interface (CG-015 Track D).
 *
 * <p>The generated class is the compile-time counterpart of the JDK dynamic proxy built by
 * {@code ServiceClientFactory}: it implements the contract interface directly, so dispatch involves
 * no reflection at all — neither at construction nor per call. It is emitted into the contract's own
 * package (origin-package pinning) with the flattened name produced by
 * {@link Identifiers#generatedClassName(TypeElement, String)}, so a nested contract
 * {@code Outer.Inner} yields {@code Outer_Inner_ServiceClientProxy}.
 *
 * <p><strong>Metadata ownership (CG-015 D1).</strong> Only two things are baked at annotation-
 * processing time: the method universe (one override per {@link OperationModel}) and the operation-id
 * string constants used to look metadata up. Everything that drives dispatch —
 * {@code oneWay}, the payload parameter index, and the {@code SecurityContext} parameter index — is
 * derived at <em>construction</em> time from the runtime {@code ServiceMethodMeta} carried by the
 * injected {@code ContractEntry}. Baking those from the source signature would silently diverge from
 * a registry built by a different mechanism (issue #88).
 *
 * <p><strong>Fail-fast (CG-015 §4.2).</strong> The generated constructor resolves every baked
 * operation id against {@code entry.operations()} and throws {@link IllegalStateException} on the
 * first miss. The message always starts with the pinned literal
 * {@code "Service client contract mismatch: "} — the same prefix {@code ServiceClientFactory} uses —
 * so the factory can recognise a companion fail-fast and surface it unwrapped.
 *
 * <p>Example shape:
 * <pre>{@code
 * @Generated("dev.vertique.codegen.services.processor.ServiceContractProcessor")
 * public final class Greeter_ServiceClientProxy implements Greeter {
 *     private final ServiceRequestSender _sender_;
 *     private final DispatchEnvelopeBuilder _envelopeBuilder_;
 *     private final ResolvedServiceTarget greetTarget;
 *     private final int greetPayloadIndex;
 *     private final int greetSecurityContextIndex;
 *     private final boolean greetOneWay;
 *
 *     public Greeter_ServiceClientProxy(ServiceRequestSender _sender_,
 *             DispatchEnvelopeBuilder _envelopeBuilder_, ContractEntry<?> _entry_) { ... }
 *
 *     @Override
 *     @SuppressWarnings("unchecked")
 *     public Future<String> greet(String name) { ... }
 *
 *     @Override
 *     public String toString() { return "ServiceProxy[Greeter]"; }
 * }
 * }</pre>
 *
 * <p>{@code equals}/{@code hashCode} are deliberately <em>not</em> overridden: the dynamic proxy
 * uses identity semantics for both, and the static proxy must match (FR-CG015-005).
 */
public final class ClientProxyEmitter {

    // --- Well-known type names ---
    private static final ClassName SERVICE_REQUEST_SENDER =
            ClassName.get("dev.vertique.services", "ServiceRequestSender");
    private static final ClassName DISPATCH_ENVELOPE_BUILDER =
            ClassName.get("dev.vertique.context", "DispatchEnvelopeBuilder");
    private static final ClassName CONTRACT_ENTRY =
            ClassName.get("dev.vertique.services", "ServiceContractRegistry", "ContractEntry");
    private static final ClassName RESOLVED_SERVICE_TARGET =
            ClassName.get("dev.vertique.services", "ResolvedServiceTarget");
    private static final ClassName SERVICE_METHOD_META =
            ClassName.get("dev.vertique.services.dispatch", "ServiceMethodMeta");
    private static final ClassName PARAM_META =
            ClassName.get("dev.vertique.services.dispatch", "ServiceMethodMeta", "ParamMeta");
    private static final ClassName PARAM_SOURCE =
            ClassName.get("dev.vertique.services.dispatch", "ServiceMethodMeta", "ParamSource");
    private static final ClassName DISPATCH_ENVELOPE = ClassName.get("dev.vertique.core.eventbus", "DispatchEnvelope");
    private static final ClassName DISPATCH_BOUNDARY = ClassName.get("dev.vertique.core.context", "DispatchBoundary");
    private static final ClassName CONTEXT_VALUES = ClassName.get("dev.vertique.context", "ContextValues");
    private static final ClassName SECURITY_CONTEXT = ClassName.get("dev.vertique.security", "SecurityContext");
    private static final ClassName FUTURES = ClassName.get("dev.vertique.core.async", "Futures");
    private static final ClassName FUTURE = ClassName.get("io.vertx.core", "Future");
    private static final ClassName LIST = ClassName.get("java.util", "List");
    private static final ClassName MAP = ClassName.get("java.util", "Map");

    private static final String PROCESSOR_FQN = "dev.vertique.codegen.services.processor.ServiceContractProcessor";

    /** Generated class-name suffix; must match {@code GeneratedNames.companionFqn}'s selection suffix. */
    private static final String SUFFIX = "_ServiceClientProxy";

    /**
     * Pinned prefix of every contract↔registry mismatch message (CG-015 §4.2). Deliberately
     * duplicated with {@code ServiceClientFactory}'s package-private constant — no shared symbol can
     * cross into user packages without new public API — and kept in sync by tests on both sides.
     */
    private static final String MISMATCH_PREFIX = "Service client contract mismatch: ";

    // --- Mangled identifiers (avoid collisions with user parameter names) ---
    private static final String SENDER_FIELD = "_sender_";
    private static final String ENVELOPE_BUILDER_FIELD = "_envelopeBuilder_";
    private static final String ENTRY_PARAM = "_entry_";
    private static final String ARGS_LOCAL = "_args_";
    private static final String PAYLOAD_LOCAL = "_payload_";
    private static final String OVERRIDES_LOCAL = "_overrides_";
    private static final String ENVELOPE_LOCAL = "_envelope_";
    private static final String DISPATCH_LOCAL = "_dispatch_";
    private static final String PAYLOAD_INDEX_HELPER = "_payloadIndex_";
    private static final String SC_INDEX_HELPER = "_securityContextIndex_";

    /**
     * Method-local names the generated dispatch body owns. A contract parameter carrying one of
     * these names would shadow the local and silently change dispatch, so the contract is skipped —
     * see {@link #reservedIdentifierCollision(ClientContractModel)}.
     */
    private static final List<String> RESERVED_PARAM_NAMES =
            List.of(ARGS_LOCAL, PAYLOAD_LOCAL, OVERRIDES_LOCAL, ENVELOPE_LOCAL, DISPATCH_LOCAL);

    /**
     * Method names the generated class owns (the two private static index helpers). A same-named
     * contract method collides only when its erased parameter list is exactly the helper's own
     * signature, {@code (ServiceMethodMeta)} — see
     * {@link #reservedIdentifierCollision(ClientContractModel)}, which checks the erasure before
     * flagging a name here. Any other overload of the same name (different arity or parameter
     * type) is a legal overload and compiles fine alongside the generated helper.
     */
    private static final List<String> RESERVED_METHOD_NAMES = List.of(PAYLOAD_INDEX_HELPER, SC_INDEX_HELPER);

    private final CodegenContext ctx;

    /**
     * Constructs an emitter bound to the given codegen context.
     *
     * @param ctx the shared codegen context; must not be {@code null}
     */
    public ClientProxyEmitter(CodegenContext ctx) {
        this.ctx = ctx;
    }

    // --- Generatability ---

    /**
     * Reports whether the given contract uses an identifier this emitter reserves for itself.
     *
     * <p>The emitter owns the names, so it owns the check. Two families are reserved:
     * <ul>
     *   <li><b>Parameter names</b> — the dispatch body declares {@code _args_}, {@code _payload_},
     *       {@code _overrides_}, {@code _envelope_}, and {@code _dispatch_} as method-locals; a
     *       contract parameter of the same name would shadow one of them and silently corrupt
     *       dispatch.</li>
     *   <li><b>Method names</b> — the class declares the private static helpers
     *       {@code _payloadIndex_} and {@code _securityContextIndex_}, each taking a single
     *       {@code ServiceMethodMeta} parameter. A same-named contract method collides only when
     *       its erased parameter list is exactly {@code (ServiceMethodMeta)} — the helper's own
     *       signature; any other overload (different arity or parameter type, e.g.
     *       {@code _payloadIndex_(String)}) is a legal overload that compiles fine alongside the
     *       generated helper and is never flagged here.</li>
     * </ul>
     *
     * <p>Fields ({@code _sender_}, {@code _envelopeBuilder_}, and the per-operation
     * {@code <method>Target} / {@code …PayloadIndex} / {@code …SecurityContextIndex} /
     * {@code …OneWay} state) and constructor locals need no check: every field read in a dispatch
     * body is {@code this.}-qualified, and the constructor sees no contract-declared identifier at
     * all.
     *
     * @param model the extracted contract-only model; must not be {@code null}
     * @return the first colliding identifier in declaration order, or {@link Optional#empty()} when
     *         the contract is safe to emit
     */
    public Optional<String> reservedIdentifierCollision(ClientContractModel model) {
        for (OperationModel op : model.operations()) {
            String methodName = methodName(op);
            if (RESERVED_METHOD_NAMES.contains(methodName) && hasServiceMethodMetaErasure(op)) {
                return Optional.of(methodName);
            }
            for (ParamModel param : op.params()) {
                if (RESERVED_PARAM_NAMES.contains(param.name())) {
                    return Optional.of(param.name());
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Reports whether the given operation's contract method has exactly one parameter whose
     * erasure is {@code ServiceMethodMeta} — the true erasure collision with the generated
     * {@code _payloadIndex_}/{@code _securityContextIndex_} helpers, both declared as
     * {@code private static int helper(ServiceMethodMeta)}.
     *
     * <p>Returns {@code false} (no collision) when {@code ServiceMethodMeta} is not resolvable on
     * the annotation-processor classpath — the emitted proxy would fail to compile for an
     * unrelated reason in that case, and this check has nothing safe to compare against.
     *
     * @param op the operation whose contract method's erasure to inspect; must not be {@code null}
     * @return {@code true} if the contract method's erased parameter list is exactly
     *         {@code (ServiceMethodMeta)}
     */
    private boolean hasServiceMethodMetaErasure(OperationModel op) {
        List<? extends VariableElement> params = op.contractMethod().getParameters();
        if (params.size() != 1) {
            return false;
        }
        TypeElement serviceMethodMetaElement = ctx.elements().getTypeElement(ServiceAnnotations.SERVICE_METHOD_META);
        if (serviceMethodMetaElement == null) {
            return false;
        }
        TypeMirror paramErasure = ctx.types().erasure(params.get(0).asType());
        TypeMirror serviceMethodMetaErasure = ctx.types().erasure(serviceMethodMetaElement.asType());
        return ctx.types().isSameType(paramErasure, serviceMethodMetaErasure);
    }

    // --- Emission ---

    /**
     * Emits the {@code {Contract}_ServiceClientProxy} class for the given validated contract-only
     * model.
     *
     * <p>Callers must first clear {@link #reservedIdentifierCollision(ClientContractModel)} — this
     * method assumes the contract owns none of the emitter's identifiers.
     *
     * <p>Write failures are reported as compiler errors on the contract element rather than thrown,
     * matching {@link ContributorEmitter}'s behaviour.
     *
     * @param model the validated contract-only model; must not be {@code null}
     */
    public void emit(ClientContractModel model) {
        TypeElement contractType = model.contractType();
        ClassName contractClass = ClassName.get(contractType);
        String proxySimpleName = Identifiers.generatedClassName(contractType, SUFFIX);
        String contractPackage = ctx.packageNameOf(contractType);

        TypeSpec.Builder classBuilder = TypeSpec.classBuilder(proxySimpleName)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addAnnotation(AnnotationSpec.builder(ClassName.get(Generated.class))
                        .addMember("value", "$S", PROCESSOR_FQN)
                        .build())
                .addSuperinterface(contractClass);

        // --- Transport fields ---
        classBuilder.addField(FieldSpec.builder(SERVICE_REQUEST_SENDER, SENDER_FIELD, Modifier.PRIVATE, Modifier.FINAL)
                .build());
        classBuilder.addField(
                FieldSpec.builder(DISPATCH_ENVELOPE_BUILDER, ENVELOPE_BUILDER_FIELD, Modifier.PRIVATE, Modifier.FINAL)
                        .build());

        // --- Per-operation runtime-derived dispatch state ---
        for (OperationModel op : model.operations()) {
            classBuilder.addField(
                    FieldSpec.builder(RESOLVED_SERVICE_TARGET, targetField(op), Modifier.PRIVATE, Modifier.FINAL)
                            .build());
            classBuilder.addField(
                    FieldSpec.builder(TypeName.INT, payloadIndexField(op), Modifier.PRIVATE, Modifier.FINAL)
                            .build());
            classBuilder.addField(FieldSpec.builder(TypeName.INT, scIndexField(op), Modifier.PRIVATE, Modifier.FINAL)
                    .build());
            classBuilder.addField(FieldSpec.builder(TypeName.BOOLEAN, oneWayField(op), Modifier.PRIVATE, Modifier.FINAL)
                    .build());
        }

        classBuilder.addMethod(buildConstructor(model, contractClass));

        for (OperationModel op : model.operations()) {
            classBuilder.addMethod(buildDispatchMethod(op));
        }

        classBuilder.addMethod(buildToString(contractType));
        classBuilder.addMethod(
                buildIndexHelper(PAYLOAD_INDEX_HELPER, CodeBlock.of("_param_.source() == $T.PAYLOAD", PARAM_SOURCE)));
        classBuilder.addMethod(buildIndexHelper(
                SC_INDEX_HELPER,
                CodeBlock.of(
                        "_param_.source() == $T.DISPATCH_CONTEXT && $T.class.getName().equals(_param_.lookupKey())",
                        PARAM_SOURCE,
                        SECURITY_CONTEXT)));

        JavaFile javaFile =
                JavaFile.builder(contractPackage, classBuilder.build()).build();
        try {
            javaFile.writeTo(ctx.filer());
        } catch (IOException e) {
            ctx.diagnostics().error(contractType, "Failed to write %s: %s", proxySimpleName, e.getMessage());
        }
    }

    // --- Constructor ---

    /**
     * Builds the public 3-arg constructor that resolves every baked operation id against the
     * registry entry and derives all runtime-owned dispatch state.
     *
     * @param model         the contract-only model; must not be {@code null}
     * @param contractClass the contract type name used for {@code ResolvedServiceTarget.of}
     * @return the constructor spec
     */
    private MethodSpec buildConstructor(ClientContractModel model, ClassName contractClass) {
        String contractFqn = model.contractType().getQualifiedName().toString();

        MethodSpec.Builder ctor = MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addJavadoc("""
                        Creates a static client proxy for {@code $L}.

                        @param $L          transport for service dispatch
                        @param $L the shared dispatch-envelope builder
                        @param $L           the registry-resolved contract entry; operations keyed by operation id
                        @throws IllegalStateException if a contract operation is missing from {@code $L.operations()}
                        """, contractFqn, SENDER_FIELD, ENVELOPE_BUILDER_FIELD, ENTRY_PARAM, ENTRY_PARAM)
                .addParameter(SERVICE_REQUEST_SENDER, SENDER_FIELD)
                .addParameter(DISPATCH_ENVELOPE_BUILDER, ENVELOPE_BUILDER_FIELD)
                .addParameter(
                        ParameterizedTypeName.get(CONTRACT_ENTRY, WildcardTypeName.subtypeOf(Object.class)),
                        ENTRY_PARAM);

        ctor.addStatement("this.$L = $L", SENDER_FIELD, SENDER_FIELD);
        ctor.addStatement("this.$L = $L", ENVELOPE_BUILDER_FIELD, ENVELOPE_BUILDER_FIELD);

        for (OperationModel op : model.operations()) {
            String metaLocal = metaLocal(op);
            String methodName = methodName(op);

            ctor.addStatement(
                    "$T $L = $L.operations().get($S)", SERVICE_METHOD_META, metaLocal, ENTRY_PARAM, op.operationName());
            ctor.beginControlFlow("if ($L == null)", metaLocal);
            ctor.addStatement(
                    "throw new $T($S)",
                    IllegalStateException.class,
                    MISMATCH_PREFIX + contractFqn + " has no registered operation '" + op.operationName()
                            + "' for method " + methodName);
            ctor.endControlFlow();

            ctor.addStatement(
                    "this.$L = $T.of($T.class, $L)",
                    targetField(op),
                    RESOLVED_SERVICE_TARGET,
                    contractClass,
                    metaLocal);
            ctor.addStatement("this.$L = $L($L)", payloadIndexField(op), PAYLOAD_INDEX_HELPER, metaLocal);
            ctor.addStatement("this.$L = $L($L)", scIndexField(op), SC_INDEX_HELPER, metaLocal);
            ctor.addStatement("this.$L = $L.oneWay()", oneWayField(op), metaLocal);
        }

        return ctor.build();
    }

    // --- Dispatch methods ---

    /**
     * Builds the override for a single contract operation.
     *
     * <p>The body is uniform across every operation: collect the declared arguments into an array,
     * pick the payload by the runtime-derived index, add the caller's explicit
     * {@code SecurityContext} argument as an override only when no ambient one is bound
     * (FR-CTX-063), build the envelope, then branch on the <em>runtime</em> {@code oneWay} flag.
     * The single unchecked cast at the end is what the declared {@code Future<T>} return type needs;
     * it is why the method carries {@code @SuppressWarnings("unchecked")}.
     *
     * <p>The override value is cast to {@code SecurityContext} unconditionally, matching the
     * reflective path's cast. Runtime metadata that marks a non-{@code SecurityContext} parameter
     * position as the SC slot therefore fails identically — with a {@link ClassCastException} — on
     * both paths, instead of one path silently forwarding a wrong-typed value.
     *
     * @param op the operation to emit; must not be {@code null}
     * @return the method spec
     */
    private MethodSpec buildDispatchMethod(OperationModel op) {
        MethodSpec.Builder method = MethodSpec.methodBuilder(methodName(op))
                .addAnnotation(Override.class)
                .addAnnotation(AnnotationSpec.builder(SuppressWarnings.class)
                        .addMember("value", "$S", "unchecked")
                        .build())
                .addModifiers(Modifier.PUBLIC)
                .returns(ParameterizedTypeName.get(FUTURE, TypeName.get(op.returnType())));

        for (ParamModel param : op.params()) {
            method.addParameter(TypeName.get(param.type()), param.name());
        }

        CodeBlock.Builder args = CodeBlock.builder().add("new $T[] {", Object.class);
        List<ParamModel> params = op.params();
        for (int i = 0; i < params.size(); i++) {
            args.add(i == 0 ? "$L" : ", $L", params.get(i).name());
        }
        args.add("}");
        method.addStatement("$T[] $L = $L", Object.class, ARGS_LOCAL, args.build());

        method.addStatement(
                "$T $L = this.$L >= 0 && this.$L < $L.length ? $L[this.$L] : null",
                Object.class,
                PAYLOAD_LOCAL,
                payloadIndexField(op),
                payloadIndexField(op),
                ARGS_LOCAL,
                ARGS_LOCAL,
                payloadIndexField(op));

        method.addStatement("$T<$T, $T> $L = $T.of()", MAP, String.class, Object.class, OVERRIDES_LOCAL, MAP);
        method.beginControlFlow(
                "if (this.$L >= 0 && this.$L < $L.length && $L[this.$L] != null && $T.current($T.class).isEmpty())",
                scIndexField(op),
                scIndexField(op),
                ARGS_LOCAL,
                ARGS_LOCAL,
                scIndexField(op),
                CONTEXT_VALUES,
                SECURITY_CONTEXT);
        // The cast is load-bearing, not cosmetic: it reproduces the ClassCastException the reflective
        // path throws (ServiceClientFactory's `(SecurityContext) args[i]`) when runtime metadata
        // marks a non-SecurityContext parameter position as the SC slot. Silently forwarding the
        // wrong-typed value would break dispatch parity and hide the drift. It is unconditional
        // rather than an instanceof guard for exactly that reason; the enclosing null check keeps
        // `null` legal.
        method.addStatement(
                "$L = $T.of($T.class.getName(), ($T) $L[this.$L])",
                OVERRIDES_LOCAL,
                MAP,
                SECURITY_CONTEXT,
                SECURITY_CONTEXT,
                ARGS_LOCAL,
                scIndexField(op));
        method.endControlFlow();

        method.addStatement(
                "$T<?> $L = this.$L.build($L, $L, $T.SERVICE_DISPATCH)",
                DISPATCH_ENVELOPE,
                ENVELOPE_LOCAL,
                ENVELOPE_BUILDER_FIELD,
                PAYLOAD_LOCAL,
                OVERRIDES_LOCAL,
                DISPATCH_BOUNDARY);

        // The oneWay flag is runtime-owned: branch on the field, never on the source annotation.
        method.addStatement("$T<?> $L", FUTURE, DISPATCH_LOCAL);
        method.beginControlFlow("if (this.$L)", oneWayField(op));
        method.addStatement(
                "$L = this.$L.sendOneWay(this.$L, $L)", DISPATCH_LOCAL, SENDER_FIELD, targetField(op), ENVELOPE_LOCAL);
        method.nextControlFlow("else");
        method.addStatement(
                "$L = this.$L.send(this.$L, $L).compose($T::toFuture)",
                DISPATCH_LOCAL,
                SENDER_FIELD,
                targetField(op),
                ENVELOPE_LOCAL,
                FUTURES);
        method.endControlFlow();
        method.addStatement(
                "return ($T) $L", ParameterizedTypeName.get(FUTURE, TypeName.get(op.returnType())), DISPATCH_LOCAL);

        return method.build();
    }

    /**
     * Builds the {@code toString()} override, matching the dynamic proxy's
     * {@code "ServiceProxy[SimpleName]"} rendering.
     *
     * @param contractType the contract interface; must not be {@code null}
     * @return the method spec
     */
    private static MethodSpec buildToString(TypeElement contractType) {
        return MethodSpec.methodBuilder("toString")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(String.class)
                .addStatement("return $S", "ServiceProxy[" + contractType.getSimpleName() + "]")
                .build();
    }

    // --- Runtime metadata helpers (emitted into the proxy) ---

    /**
     * Builds a private static index-helper method: scans {@code _meta_.params()} for the first
     * parameter matching {@code condition}, returning its index, or {@code -1} when none match.
     *
     * <p>Shared by the payload-index and {@code SecurityContext}-index helpers ({@code
     * PAYLOAD_INDEX_HELPER} / {@code SC_INDEX_HELPER}), which differ only in the match condition
     * evaluated against the loop-local {@code _param_}.
     *
     * @param methodName the generated method's name
     * @param condition  the boolean condition tested against the loop-local {@code _param_} (bound
     *                   to {@code _params_.get(_i_)}); must not be {@code null}
     * @return the method spec
     */
    private static MethodSpec buildIndexHelper(String methodName, CodeBlock condition) {
        return MethodSpec.methodBuilder(methodName)
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(TypeName.INT)
                .addParameter(SERVICE_METHOD_META, "_meta_")
                .addStatement("$T<$T> _params_ = _meta_.params()", LIST, PARAM_META)
                .beginControlFlow("for (int _i_ = 0; _i_ < _params_.size(); _i_++)")
                .addStatement("$T _param_ = _params_.get(_i_)", PARAM_META)
                .beginControlFlow("if ($L)", condition)
                .addStatement("return _i_")
                .endControlFlow()
                .endControlFlow()
                .addStatement("return -1")
                .build();
    }

    // --- Identifier derivation ---

    /**
     * Returns the contract method's simple name, used as the base for all per-operation identifiers.
     *
     * @param op the operation; must not be {@code null}
     * @return the contract method name
     */
    private static String methodName(OperationModel op) {
        return op.contractMethod().getSimpleName().toString();
    }

    /**
     * Returns the field name holding the operation's {@code ResolvedServiceTarget}.
     *
     * @param op the operation; must not be {@code null}
     * @return a valid Java identifier
     */
    private static String targetField(OperationModel op) {
        return methodName(op) + "Target";
    }

    /**
     * Returns the field name holding the operation's runtime-derived payload parameter index.
     *
     * @param op the operation; must not be {@code null}
     * @return a valid Java identifier
     */
    private static String payloadIndexField(OperationModel op) {
        return methodName(op) + "PayloadIndex";
    }

    /**
     * Returns the field name holding the operation's runtime-derived {@code SecurityContext}
     * parameter index.
     *
     * @param op the operation; must not be {@code null}
     * @return a valid Java identifier
     */
    private static String scIndexField(OperationModel op) {
        return methodName(op) + "SecurityContextIndex";
    }

    /**
     * Returns the field name holding the operation's runtime-derived {@code oneWay} flag.
     *
     * @param op the operation; must not be {@code null}
     * @return a valid Java identifier
     */
    private static String oneWayField(OperationModel op) {
        return methodName(op) + "OneWay";
    }

    /**
     * Returns the constructor-local variable name holding the operation's resolved
     * {@code ServiceMethodMeta}.
     *
     * @param op the operation; must not be {@code null}
     * @return a valid Java identifier
     */
    private static String metaLocal(OperationModel op) {
        return "_" + methodName(op) + "Meta_";
    }
}
