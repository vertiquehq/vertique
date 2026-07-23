// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.workflow.processor.validate;

import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.Diagnostics;
import dev.vertique.codegen.workflow.processor.scan.ContractModel;
import dev.vertique.codegen.workflow.processor.scan.OperationModel;
import dev.vertique.codegen.workflow.processor.scan.OperationRole;
import dev.vertique.codegen.workflow.processor.scan.ParamRole;
import dev.vertique.codegen.workflow.processor.scan.ParamRoleModel;
import java.util.List;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;

/**
 * Compile-time parameter-annotation validator for clean single-role operations in a
 * {@code @WorkflowContract} interface.
 *
 * <p>Checked rules (only for clean single-role, non-default operations):
 * <ul>
 *   <li>{@code @WorkflowStart} ops:
 *     <ul>
 *       <li>{@code @IdempotencyKey} and {@code @BusinessKey} params must have type {@code String}.</li>
 *       <li>{@code @SubjectRef} params must have type {@code WorkflowSubjectRef}.</li>
 *       <li>Duplicates of {@code @IdempotencyKey}, {@code @BusinessKey}, and {@code @SubjectRef}
 *           are not allowed.</li>
 *     </ul>
 *   </li>
 *   <li>{@code @WorkflowSignal} ops:
 *     <ul>
 *       <li>{@code @SignalDedupKey} params must have type {@code String}.</li>
 *       <li>Duplicates of {@code @SignalDedupKey} are not allowed.</li>
 *     </ul>
 *   </li>
 *   <li>{@code @WorkflowQuery} ops: no param-annotation checks.</li>
 * </ul>
 *
 * <p>Operations with default or conflicting/missing roles are skipped — those are reported by
 * {@link ContractShapeValidator}.
 */
public final class ParamAnnotationValidator {

    // --- Runtime type FQNs ---

    private static final String WORKFLOW_SUBJECT_REF_FQN = "dev.vertique.workflow.subject.WorkflowSubjectRef";

    private final CodegenContext ctx;

    /**
     * Constructs a validator bound to the given codegen context.
     *
     * @param ctx the codegen context; must not be {@code null}
     */
    public ParamAnnotationValidator(CodegenContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Validates the parameter annotations of all clean single-role operations in the given contract
     * model, emitting compiler error diagnostics for every violation. Returns {@code true} when no
     * errors were found.
     *
     * @param model the scanned contract model to validate; must not be {@code null}
     * @return {@code true} if all checked operations have valid parameter annotations; {@code false}
     *         otherwise
     */
    public boolean validate(ContractModel model) {
        String fqn = model.contractType().getQualifiedName().toString();
        TypeElement subjectRefElement = ctx.elements().getTypeElement(WORKFLOW_SUBJECT_REF_FQN);

        boolean ok = true;
        for (OperationModel op : model.operations()) {
            // Only inspect clean single-role, non-default operations
            if (!op.isCleanSingleRoleOp()) {
                continue;
            }
            OperationRole role = op.role().get();
            switch (role) {
                case START -> ok &= validateStartParams(fqn, op, subjectRefElement);
                case SIGNAL -> ok &= validateSignalParams(fqn, op);
                case QUERY -> {
                    // No param-annotation checks for QUERY
                }
            }
        }
        return ok;
    }

    // --- Role-specific param validators ---

    /**
     * Validates parameter annotation type and cardinality rules for a {@code @WorkflowStart} method.
     *
     * @param fqn              the contract FQN for diagnostic messages
     * @param op               the operation model for the start method
     * @param subjectRefElement the {@code WorkflowSubjectRef} type element, or {@code null}
     * @return {@code true} if no errors were emitted
     */
    private boolean validateStartParams(String fqn, OperationModel op, TypeElement subjectRefElement) {
        String m = op.methodName();
        boolean ok = true;

        // @IdempotencyKey — must be String, at most one
        ok &= validateParamRole(
                fqn,
                m,
                op,
                ParamRole.IDEMPOTENCY_KEY,
                "IdempotencyKey",
                "String",
                null /* String is checked by name comparison */);
        // @BusinessKey — must be String, at most one
        ok &= validateParamRole(fqn, m, op, ParamRole.BUSINESS_KEY, "BusinessKey", "String", null);
        // @SubjectRef — must be WorkflowSubjectRef, at most one
        ok &= validateParamRole(
                fqn, m, op, ParamRole.SUBJECT_REF, "SubjectRef", "WorkflowSubjectRef", subjectRefElement);

        return ok;
    }

    /**
     * Validates parameter annotation type and cardinality rules for a {@code @WorkflowSignal} method.
     *
     * @param fqn the contract FQN for diagnostic messages
     * @param op  the operation model for the signal method
     * @return {@code true} if no errors were emitted
     */
    private boolean validateSignalParams(String fqn, OperationModel op) {
        String m = op.methodName();
        // @SignalDedupKey — must be String, at most one
        return validateParamRole(fqn, m, op, ParamRole.SIGNAL_DEDUP_KEY, "SignalDedupKey", "String", null);
    }

    /**
     * Validates the type and cardinality of parameters with the given role.
     *
     * <p>When {@code targetElement} is {@code null}, the required type is checked by comparing
     * {@code p.type().toString()} to {@code "java.lang.String"} (for String-typed roles). When
     * {@code targetElement} is non-null, the required type is checked via erased same-type comparison.
     *
     * @param fqn           the contract FQN for messages
     * @param m             the method name for messages
     * @param op            the operation model
     * @param role          the param role to check
     * @param annoSimpleName the annotation simple name (without {@code @}) for messages
     * @param requiredType  the human-readable required type name for messages
     * @param targetElement the target type element for type checking, or {@code null} for String check
     * @return {@code true} if no errors were emitted
     */
    private boolean validateParamRole(
            String fqn,
            String m,
            OperationModel op,
            ParamRole role,
            String annoSimpleName,
            String requiredType,
            TypeElement targetElement) {
        List<ParamRoleModel> matching =
                op.params().stream().filter(p -> p.role() == role).toList();
        if (matching.isEmpty()) {
            return true;
        }

        boolean ok = true;

        // Type check: each param with this role must have the required type
        for (ParamRoleModel p : matching) {
            if (!hasRequiredType(p.type(), requiredType, targetElement)) {
                ctx.diagnostics()
                        .error(
                                p.element(),
                                Diagnostics.workflowParamAnnotationType(fqn, m, annoSimpleName, requiredType));
                ok = false;
            }
        }

        // Cardinality check: at most one param with this role
        if (matching.size() > 1) {
            ctx.diagnostics().error(op.method(), Diagnostics.workflowDuplicateParamAnnotation(fqn, m, annoSimpleName));
            ok = false;
        }

        return ok;
    }

    // --- Type helpers ---

    /**
     * Returns {@code true} when the given type satisfies the required type constraint.
     *
     * <p>When {@code targetElement} is non-null, the check uses erased same-type comparison.
     * When {@code targetElement} is {@code null}, the check uses a string comparison against
     * {@code "java.lang.String"} (the only String-typed param role).
     *
     * @param type          the parameter type to test
     * @param requiredType  the human-readable required type name (used for String detection)
     * @param targetElement the target type element, or {@code null} for String check
     * @return {@code true} if the type satisfies the constraint
     */
    private boolean hasRequiredType(TypeMirror type, String requiredType, TypeElement targetElement) {
        if (targetElement != null) {
            // Use erased same-type comparison for non-String types
            return ctx.types().isSameType(ctx.types().erasure(type), ctx.types().erasure(targetElement.asType()));
        }
        // String check by type name
        return type.toString().equals("java.lang.String");
    }
}
