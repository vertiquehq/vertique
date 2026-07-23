// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.workflow.processor.emit;

import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.support.Identifiers;
import dev.vertique.codegen.workflow.processor.scan.ContractModel;
import dev.vertique.codegen.workflow.processor.scan.OperationModel;
import dev.vertique.codegen.workflow.processor.scan.OperationRole;
import dev.vertique.codegen.workflow.processor.scan.ParamRole;
import dev.vertique.codegen.workflow.processor.scan.ParamRoleModel;
import java.io.IOException;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.processing.Generated;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;

/**
 * Emitter that generates a {@code {Contract}_WorkflowClientProxy} class for each validated
 * {@code @WorkflowContract} interface.
 *
 * <p>The generated class is {@code public final}, implements the contract interface, and delegates
 * each operation method to {@code WorkflowOperations} with zero reflection:
 * <ul>
 *   <li>{@code @WorkflowStart} methods call {@code ops.start(new StartCommand(...))} with the
 *       idempotency key, business key, and subject ref extracted from the payload or explicit
 *       parameters.</li>
 *   <li>{@code @WorkflowSignal} methods call {@code ops.signal(...)} with the dedup key extracted
 *       from the signal payload or an explicit parameter.</li>
 *   <li>{@code @WorkflowQuery} methods call {@code ops.query(id)}.</li>
 * </ul>
 *
 * <p>The generated class lands in the contract's own package so that {@code WorkflowClientFactory}
 * can resolve it by name at runtime; see ADR-0073.
 *
 * <p>This emitter is only called for validated contracts; every operation is assumed to be a
 * clean single-role method with correct parameter structure.
 */
public final class WorkflowProxyEmitter {

    private static final String PROCESSOR_FQN = "dev.vertique.codegen.workflow.processor.WorkflowContractProcessor";
    private static final String SUFFIX = "_WorkflowClientProxy";

    // --- Runtime type ClassNames ---

    private static final String OPS_PKG = "dev.vertique.workflow.ops";
    private static final ClassName WORKFLOW_OPERATIONS = ClassName.get(OPS_PKG, "WorkflowOperations");
    private static final ClassName START_COMMAND = ClassName.get(OPS_PKG, "StartCommand");

    private static final String CONTRACT_PKG = "dev.vertique.workflow.contract";
    private static final ClassName IDEMPOTENCY_KEYED = ClassName.get(CONTRACT_PKG, "IdempotencyKeyed");
    private static final ClassName BUSINESS_KEYED = ClassName.get(CONTRACT_PKG, "BusinessKeyed");
    private static final ClassName SUBJECT_REFERENCED = ClassName.get(CONTRACT_PKG, "SubjectReferenced");
    private static final ClassName SIGNAL_DEDUP_KEYED = ClassName.get(CONTRACT_PKG, "SignalDedupKeyed");

    private static final ClassName JAKARTA_INJECT = ClassName.get("jakarta.inject", "Inject");

    private final CodegenContext ctx;

    /**
     * Creates an emitter bound to the given codegen context.
     *
     * @param ctx the shared codegen context; must not be {@code null}
     */
    public WorkflowProxyEmitter(CodegenContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Emits the {@code {Contract}_WorkflowClientProxy} class for the given validated contract model.
     *
     * <p>Writes the generated source file to the {@code Filer} via {@link CodegenContext#filer()}.
     * If writing fails, a compiler error diagnostic is emitted instead.
     *
     * @param model the validated contract model; must not be {@code null}
     */
    public void emit(ContractModel model) {
        TypeElement contract = model.contractType();
        String packageName = ctx.packageNameOf(contract);
        String generatedSimpleName = Identifiers.generatedClassName(contract, SUFFIX);
        ClassName contractClassName = ClassName.get(contract);

        TypeSpec.Builder typeBuilder = TypeSpec.classBuilder(generatedSimpleName)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addSuperinterface(contractClassName)
                .addAnnotation(AnnotationSpec.builder(Generated.class)
                        .addMember("value", "$S", PROCESSOR_FQN)
                        .build())
                .addJavadoc(
                        "Generated zero-reflection workflow client proxy for {@link $T}.\n\n"
                                + "<p>Selected at runtime by {@code WorkflowClientFactory} when present on the\n"
                                + "classpath. Delegates every contract method to {@code WorkflowOperations}.\n",
                        contractClassName)
                .addField(WORKFLOW_OPERATIONS, "ops", Modifier.PRIVATE, Modifier.FINAL)
                .addMethod(buildConstructor(generatedSimpleName))
                .addMethod(buildToString(contractClassName));

        for (OperationModel op : model.operations()) {
            typeBuilder.addMethod(buildOperationMethod(op, model));
        }

        JavaFile javaFile = JavaFile.builder(packageName, typeBuilder.build()).build();
        try {
            javaFile.writeTo(ctx.filer());
        } catch (IOException e) {
            ctx.diagnostics()
                    .error(
                            contract,
                            "Failed to write generated source file '%s.%s': %s",
                            packageName,
                            generatedSimpleName,
                            e.getMessage());
        }
    }

    // --- Constructor ---

    /**
     * Builds the {@code @Inject} constructor that receives a {@code WorkflowOperations} instance.
     *
     * @param generatedSimpleName the simple name of the generated class
     * @return the constructor method spec
     */
    private static MethodSpec buildConstructor(String generatedSimpleName) {
        return MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(AnnotationSpec.builder(JAKARTA_INJECT).build())
                .addParameter(WORKFLOW_OPERATIONS, "ops")
                .addJavadoc("Constructs the generated proxy, receiving the workflow operations facade.\n\n"
                        + "@param ops the workflow operations to delegate contract methods to\n")
                .addStatement("this.ops = ops")
                .build();
    }

    // --- Operation methods ---

    /**
     * Builds the {@code @Override} method for a single operation, delegating to the appropriate
     * {@code WorkflowOperations} method based on the operation role.
     *
     * @param op    the operation model; must have exactly one role
     * @param model the parent contract model (used for {@code definitionId} and
     *              {@code definitionVersion})
     * @return the method spec
     */
    private static MethodSpec buildOperationMethod(OperationModel op, ContractModel model) {
        MethodSpec.Builder method = MethodSpec.methodBuilder(op.methodName())
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeName.get(op.returnType()));

        for (ParamRoleModel p : op.params()) {
            method.addParameter(TypeName.get(p.type()), p.name());
        }

        OperationRole role = op.role().orElseThrow();
        switch (role) {
            case START -> addStartBody(method, op, model);
            case SIGNAL -> addSignalBody(method, op);
            case QUERY -> addQueryBody(method, op);
        }

        return method.build();
    }

    /**
     * Adds the START operation body — constructs a {@code StartCommand} and calls
     * {@code ops.start(...)}.
     *
     * @param method the method builder to add the body to
     * @param op     the START operation model
     * @param model  the contract model (for {@code definitionId} and {@code definitionVersion})
     */
    private static void addStartBody(MethodSpec.Builder method, OperationModel op, ContractModel model) {
        String payloadName =
                op.firstParamWithRole(ParamRole.PAYLOAD).orElseThrow().name();

        // Idempotency key: explicit @IdempotencyKey param wins; else cast payload to IdempotencyKeyed
        CodeBlock idemExpr = op.firstParamWithRole(ParamRole.IDEMPOTENCY_KEY)
                .map(p -> CodeBlock.of("$L", p.name()))
                .orElse(CodeBlock.of("(($T) $L).idempotencyKey()", IDEMPOTENCY_KEYED, payloadName));

        // Business key (OPTIONAL): explicit @BusinessKey param wins; else a RUNTIME instanceof check on the
        // payload — matching WorkflowProxyValidator.precompileHandlers exactly. A compile-time decision based
        // on the declared payload type would drop the key when a runtime SUBTYPE implements BusinessKeyed but
        // the declared type does not.
        // The instanceof operand is cast to Object: a final payload type that does not implement the marker
        // would otherwise make `payload instanceof Marker` an "inconvertible types" compile error. Casting to
        // Object mirrors the reflective handler (which holds the payload as Object) and is always legal.
        CodeBlock bizExpr = op.firstParamWithRole(ParamRole.BUSINESS_KEY)
                .map(p -> CodeBlock.of("$L", p.name()))
                .orElseGet(() -> {
                    String bk = uniqueLocal("bk", op);
                    return CodeBlock.of(
                            "(Object) $L instanceof $T $L ? $L.businessKey() : null",
                            payloadName,
                            BUSINESS_KEYED,
                            bk,
                            bk);
                });

        // Subject ref (OPTIONAL): explicit @SubjectRef param wins; else a RUNTIME instanceof check (same
        // rationale as the business key above, including the Object cast on the operand).
        CodeBlock subjExpr = op.firstParamWithRole(ParamRole.SUBJECT_REF)
                .map(p -> CodeBlock.of("$L", p.name()))
                .orElseGet(() -> {
                    String sr = uniqueLocal("sr", op);
                    return CodeBlock.of(
                            "(Object) $L instanceof $T $L ? $L.subjectRef() : null",
                            payloadName,
                            SUBJECT_REFERENCED,
                            sr,
                            sr);
                });

        // Version literal: always pin the compiled version as a Long literal (e.g. "5L")
        CodeBlock versionExpr = CodeBlock.of("$LL", model.definitionVersion());

        // Qualify the receiver as this.ops: an operation parameter may legitimately be named "ops"
        // (the runtime JDK proxy captures ops in a closure and has no such collision), and a bare
        // ops would then resolve to that parameter and fail to compile.
        method.addStatement(
                "return this.ops.start(new $T($S, $L, $L, $L, $L, $L))",
                START_COMMAND,
                model.definitionId(),
                payloadName,
                idemExpr,
                bizExpr,
                subjExpr,
                versionExpr);
    }

    /**
     * Adds the SIGNAL operation body — extracts the dedup key and calls {@code ops.signal(...)}.
     *
     * @param method the method builder to add the body to
     * @param op     the SIGNAL operation model
     */
    private static void addSignalBody(MethodSpec.Builder method, OperationModel op) {
        String idName =
                op.firstParamWithRole(ParamRole.INSTANCE_ID).orElseThrow().name();
        String payloadName =
                op.firstParamWithRole(ParamRole.PAYLOAD).orElseThrow().name();

        // Dedup key: explicit @SignalDedupKey param wins; else cast payload to SignalDedupKeyed
        CodeBlock dedupExpr = op.firstParamWithRole(ParamRole.SIGNAL_DEDUP_KEY)
                .map(p -> CodeBlock.of("$L", p.name()))
                .orElse(CodeBlock.of("(($T) $L).dedupKey()", SIGNAL_DEDUP_KEYED, payloadName));

        // Qualify the receiver as this.ops (see addStartBody — guards against an operation parameter named "ops").
        method.addStatement("return this.ops.signal($L, $S, $L, $L)", idName, op.signalName(), payloadName, dedupExpr);
    }

    /**
     * Adds the QUERY operation body — calls {@code this.ops.query(id)}.
     *
     * <p>A {@code @WorkflowQuery} is constrained (by both the validator and the runtime
     * {@code WorkflowProxyValidator}) to return exactly {@code Future<WorkflowView>}, which is what
     * {@code WorkflowOperations.query} returns — so the call is emitted directly with no adaptation.
     *
     * @param method the method builder to add the body to
     * @param op     the QUERY operation model
     */
    private static void addQueryBody(MethodSpec.Builder method, OperationModel op) {
        String idName =
                op.firstParamWithRole(ParamRole.INSTANCE_ID).orElseThrow().name();
        method.addStatement("return this.ops.query($L)", idName);
    }

    /**
     * Returns a local-variable name with the given base that does not collide with any of the
     * operation's parameter names, so an emitted {@code instanceof} pattern variable cannot shadow
     * (and fail to compile against) a user-named parameter.
     *
     * @param base the desired suffix after the {@code __} prefix (e.g. {@code "bk"})
     * @param op   the operation whose parameter names must be avoided
     * @return a collision-free local name (e.g. {@code "__bk"})
     */
    private static String uniqueLocal(String base, OperationModel op) {
        Set<String> taken = op.params().stream().map(ParamRoleModel::name).collect(Collectors.toSet());
        String name = "__" + base;
        while (taken.contains(name)) {
            name = name + "_";
        }
        return name;
    }

    // --- toString ---

    /**
     * Builds the {@code toString()} override that mirrors the reflective proxy's form:
     * {@code "WorkflowProxy[" + Contract.class.getName() + "]"}.
     *
     * @param contractClassName the {@link ClassName} of the contract interface
     * @return the {@code toString} method spec
     */
    private static MethodSpec buildToString(ClassName contractClassName) {
        return MethodSpec.methodBuilder("toString")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(String.class)
                .addStatement("return $S + $T.class.getName() + $S", "WorkflowProxy[", contractClassName, "]")
                .build();
    }
}
