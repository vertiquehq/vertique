// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.workflow.processor.scan;

import dev.vertique.codegen.AnnotationMirrors;
import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.support.MethodOverrides;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;

/**
 * APT-side scanner that reads a {@code @WorkflowContract} interface into a {@link ContractModel}.
 *
 * <p>The scanner is <em>tolerant</em>: it classifies every non-static, non-{@code Object} method —
 * including malformed ones — and emits no diagnostics. All shape validation is deferred to the
 * validator slice so that the model provides a complete picture of the contract regardless of
 * correctness.
 *
 * <p>Parameter roles are assigned in precedence order: annotation-based roles ({@code @IdempotencyKey},
 * {@code @BusinessKey}, {@code @SubjectRef}, {@code @SignalDedupKey}) win over the type-based
 * {@code WorkflowInstanceId} role, which wins over the catch-all {@code PAYLOAD} role.
 *
 * <p>The two required-key flags ({@code payloadIsIdempotencyKeyed}, {@code payloadIsSignalDedupKeyed})
 * are set when the contract has exactly one PAYLOAD parameter whose declared type is assignable to the
 * corresponding marker interface; both are {@code false} when zero or more than one PAYLOAD parameter
 * exists. The optional business-key / subject-ref sources are intentionally <em>not</em> precomputed —
 * the generated proxy resolves them at runtime via {@code instanceof}, matching the reflective handler.
 */
public final class ContractScanner {

    // --- Annotation FQNs ---

    private static final String WORKFLOW_CONTRACT_FQN = "dev.vertique.workflow.contract.WorkflowContract";
    private static final String WORKFLOW_START_FQN = "dev.vertique.workflow.contract.WorkflowStart";
    private static final String WORKFLOW_SIGNAL_FQN = "dev.vertique.workflow.contract.WorkflowSignal";
    private static final String WORKFLOW_QUERY_FQN = "dev.vertique.workflow.contract.WorkflowQuery";

    private static final String IDEMPOTENCY_KEY_FQN = "dev.vertique.workflow.contract.IdempotencyKey";
    private static final String BUSINESS_KEY_FQN = "dev.vertique.workflow.contract.BusinessKey";
    private static final String SUBJECT_REF_FQN = "dev.vertique.workflow.contract.SubjectRef";
    private static final String SIGNAL_DEDUP_KEY_FQN = "dev.vertique.workflow.contract.SignalDedupKey";

    // --- Runtime type FQNs ---

    private static final String WORKFLOW_INSTANCE_ID_FQN = "dev.vertique.workflow.ops.WorkflowInstanceId";

    // --- Marker interface FQNs ---

    private static final String IDEMPOTENCY_KEYED_FQN = "dev.vertique.workflow.contract.IdempotencyKeyed";
    private static final String SIGNAL_DEDUP_KEYED_FQN = "dev.vertique.workflow.contract.SignalDedupKeyed";

    // --- Annotation attribute names ---

    private static final String ATTR_DEFINITION_ID = "definitionId";
    private static final String ATTR_DEFINITION_VERSION = "definitionVersion";
    private static final String ATTR_VALUE = "value";

    private final CodegenContext ctx;

    /**
     * Constructs a scanner bound to the given codegen context.
     *
     * @param ctx the codegen context; must not be {@code null}
     */
    public ContractScanner(CodegenContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Scans a {@code @WorkflowContract} interface into a {@link ContractModel}.
     *
     * @param contract the contract interface element; must carry {@code @WorkflowContract}
     * @return the fully populated model
     * @throws IllegalStateException when {@code contract} does not carry {@code @WorkflowContract}
     */
    public ContractModel scan(TypeElement contract) {
        AnnotationMirror mirror = AnnotationMirrors.findByFqn(contract, WORKFLOW_CONTRACT_FQN)
                .orElseThrow(() -> new IllegalStateException(
                        "scan() called on a type without @WorkflowContract: " + contract.getQualifiedName()));

        String definitionId = ctx.annotations()
                .attribute(mirror, ATTR_DEFINITION_ID, String.class)
                .orElse("");
        long definitionVersion = ctx.annotations()
                .attribute(mirror, ATTR_DEFINITION_VERSION, Long.class)
                .orElse(0L);

        List<OperationModel> operations = scanOperations(contract);

        return new ContractModel(contract, definitionId, definitionVersion, operations);
    }

    // --- Private scanning helpers ---

    /**
     * Collects and scans all non-static, non-{@code Object} methods from the contract element,
     * including methods inherited from super-interfaces. This mirrors the semantics of
     * {@code Class.getMethods()}.
     *
     * <p><strong>Deduplication:</strong> the raw list from
     * {@link javax.lang.model.util.Elements#getAllMembers(TypeElement)} is first deduplicated by
     * {@link dev.vertique.codegen.support.MethodOverrides#deduplicateByErasedSignature} before being
     * mapped to {@link OperationModel}s, mirroring {@code Class#getMethods()} semantics for parity
     * with {@code KafkaListenerScanner}: an override chain collapses to the most-derived declaration
     * (so a {@code @WorkflowContract} that re-declares a base-interface method with
     * {@code @WorkflowStart} yields one operation, not two — avoiding a duplicate-method compile
     * error and spurious "no workflow role annotation" errors), while a covariant sibling pair (same
     * erased signature, distinct return types from unrelated super-interfaces) is <em>preserved</em>
     * so the validator rejects the un-implementable contract exactly as the reflective runtime does.
     *
     * @param contract the contract interface element
     * @return the ordered list of operation models
     */
    private List<OperationModel> scanOperations(TypeElement contract) {
        List<ExecutableElement> filtered = new ArrayList<>();
        for (ExecutableElement method : ElementFilter.methodsIn(ctx.elements().getAllMembers(contract))) {
            if (method.getModifiers().contains(Modifier.STATIC)) {
                continue;
            }
            // Exclude methods declared directly on java.lang.Object.
            if (method.getEnclosingElement() instanceof TypeElement enclosing
                    && enclosing.getQualifiedName().contentEquals("java.lang.Object")) {
                continue;
            }
            filtered.add(method);
        }
        List<ExecutableElement> deduplicated = MethodOverrides.deduplicateByErasedSignature(filtered, ctx.types());
        List<OperationModel> operations = new ArrayList<>();
        for (ExecutableElement method : deduplicated) {
            operations.add(scanOperation(method));
        }
        return operations;
    }

    /**
     * Scans a single method into an {@link OperationModel}.
     *
     * @param method the method element to scan
     * @return the operation model
     */
    private OperationModel scanOperation(ExecutableElement method) {
        boolean isDefault = method.getModifiers().contains(Modifier.DEFAULT);

        List<OperationRole> declaredRoles = buildDeclaredRoles(method);

        String signalName = buildSignalName(method, declaredRoles);

        List<ParamRoleModel> params = buildParams(method);

        TypeMirror returnType = method.getReturnType();

        // The two REQUIRED key-source flags are meaningful only when there is exactly one PAYLOAD
        // parameter; otherwise the payload type is null and both flags are false. (The OPTIONAL
        // business/subject sources are resolved at runtime via instanceof in the generated proxy, so
        // they are not precomputed here.)
        TypeMirror payloadType =
                singlePayloadParam(params).map(ParamRoleModel::type).orElse(null);

        return new OperationModel(
                method,
                method.getSimpleName().toString(),
                declaredRoles,
                signalName,
                isDefault,
                returnType,
                params,
                isAssignableTo(payloadType, IDEMPOTENCY_KEYED_FQN),
                isAssignableTo(payloadType, SIGNAL_DEDUP_KEYED_FQN));
    }

    /**
     * Returns the single {@link ParamRole#PAYLOAD} parameter when the method declares exactly one;
     * otherwise {@link Optional#empty()}.
     *
     * @param params the classified parameters
     * @return the sole payload parameter, or empty when there are zero or more than one
     */
    private static Optional<ParamRoleModel> singlePayloadParam(List<ParamRoleModel> params) {
        List<ParamRoleModel> payloads =
                params.stream().filter(p -> p.role() == ParamRole.PAYLOAD).toList();
        return payloads.size() == 1 ? Optional.of(payloads.get(0)) : Optional.empty();
    }

    /**
     * Builds the ordered list of declared operation roles for the given method by checking for each
     * workflow-role annotation in fixed order: START, SIGNAL, QUERY.
     *
     * @param method the method to inspect
     * @return the list of roles whose annotation is present; size 0, 1, or more
     */
    private List<OperationRole> buildDeclaredRoles(ExecutableElement method) {
        List<OperationRole> roles = new ArrayList<>();
        if (AnnotationMirrors.findByFqn(method, WORKFLOW_START_FQN).isPresent()) {
            roles.add(OperationRole.START);
        }
        if (AnnotationMirrors.findByFqn(method, WORKFLOW_SIGNAL_FQN).isPresent()) {
            roles.add(OperationRole.SIGNAL);
        }
        if (AnnotationMirrors.findByFqn(method, WORKFLOW_QUERY_FQN).isPresent()) {
            roles.add(OperationRole.QUERY);
        }
        return roles;
    }

    /**
     * Resolves the signal name from {@code @WorkflowSignal.value()} when the SIGNAL role is
     * present; returns {@code null} otherwise.
     *
     * @param method        the method to inspect
     * @param declaredRoles the already-computed declared roles list
     * @return the signal name string, or {@code null}
     */
    private String buildSignalName(ExecutableElement method, List<OperationRole> declaredRoles) {
        if (!declaredRoles.contains(OperationRole.SIGNAL)) {
            return null;
        }
        return AnnotationMirrors.findByFqn(method, WORKFLOW_SIGNAL_FQN)
                .flatMap(m -> ctx.annotations().attribute(m, ATTR_VALUE, String.class))
                .orElse("");
    }

    /**
     * Classifies every parameter in the method according to the precedence rules described in
     * {@link ContractScanner}.
     *
     * @param method the method whose parameters to classify
     * @return the ordered list of parameter role models
     */
    private List<ParamRoleModel> buildParams(ExecutableElement method) {
        TypeElement instanceIdElement = ctx.elements().getTypeElement(WORKFLOW_INSTANCE_ID_FQN);
        // Erase once per method rather than per parameter.
        TypeMirror instanceIdErasure =
                instanceIdElement == null ? null : ctx.types().erasure(instanceIdElement.asType());

        List<ParamRoleModel> params = new ArrayList<>();
        for (VariableElement param : method.getParameters()) {
            ParamRole role = classifyParam(param, instanceIdErasure);
            params.add(new ParamRoleModel(param, param.getSimpleName().toString(), param.asType(), role));
        }
        return params;
    }

    /**
     * Determines the {@link ParamRole} for a single parameter using the classification precedence:
     * annotation-based roles first ({@code @IdempotencyKey}, {@code @BusinessKey},
     * {@code @SubjectRef}, {@code @SignalDedupKey}), then type-based ({@code WorkflowInstanceId}),
     * then the catch-all {@code PAYLOAD}.
     *
     * @param param             the parameter element to classify
     * @param instanceIdErasure the erased {@code WorkflowInstanceId} type, or {@code null} if it is
     *                          not on the compile classpath
     * @return the assigned {@link ParamRole}
     */
    private ParamRole classifyParam(VariableElement param, TypeMirror instanceIdErasure) {
        if (AnnotationMirrors.findByFqn(param, IDEMPOTENCY_KEY_FQN).isPresent()) {
            return ParamRole.IDEMPOTENCY_KEY;
        }
        if (AnnotationMirrors.findByFqn(param, BUSINESS_KEY_FQN).isPresent()) {
            return ParamRole.BUSINESS_KEY;
        }
        if (AnnotationMirrors.findByFqn(param, SUBJECT_REF_FQN).isPresent()) {
            return ParamRole.SUBJECT_REF;
        }
        if (AnnotationMirrors.findByFqn(param, SIGNAL_DEDUP_KEY_FQN).isPresent()) {
            return ParamRole.SIGNAL_DEDUP_KEY;
        }
        if (instanceIdErasure != null
                && ctx.types().isSameType(ctx.types().erasure(param.asType()), instanceIdErasure)) {
            return ParamRole.INSTANCE_ID;
        }
        return ParamRole.PAYLOAD;
    }

    /**
     * Returns {@code true} when {@code type} is non-{@code null} and assignable to the type
     * identified by {@code markerFqn}; {@code false} when {@code type} is {@code null} (no single
     * payload parameter), when the marker type element cannot be found on the compile classpath, or
     * when the types are not assignable.
     *
     * @param type      the type to test, or {@code null}
     * @param markerFqn the fully-qualified name of the target interface
     * @return {@code true} if assignable
     */
    private boolean isAssignableTo(TypeMirror type, String markerFqn) {
        if (type == null) {
            return false;
        }
        TypeElement markerElement = ctx.elements().getTypeElement(markerFqn);
        if (markerElement == null) {
            return false;
        }
        return ctx.types().isAssignable(type, markerElement.asType());
    }
}
