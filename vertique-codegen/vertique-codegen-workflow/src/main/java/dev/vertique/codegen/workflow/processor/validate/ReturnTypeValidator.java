// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.workflow.processor.validate;

import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.Diagnostics;
import dev.vertique.codegen.workflow.processor.scan.ContractModel;
import dev.vertique.codegen.workflow.processor.scan.OperationModel;
import dev.vertique.codegen.workflow.processor.scan.OperationRole;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;

/**
 * Compile-time return-type validator for clean single-role operations in a
 * {@code @WorkflowContract} interface.
 *
 * <p>Checked rules (only applied to clean single-role, non-default operations):
 * <ul>
 *   <li>{@code @WorkflowStart} — must return {@code Future<WorkflowInstanceId>}.</li>
 *   <li>{@code @WorkflowSignal} — must return {@code Future<Void>}.</li>
 *   <li>{@code @WorkflowQuery} — must return exactly {@code Future<WorkflowView>} (V1 single
 *       untyped query surface; matches the runtime {@code WorkflowProxyValidator}).</li>
 * </ul>
 *
 * <p>Operations with default or conflicting/missing roles are skipped — those are reported by
 * {@link ContractShapeValidator}.
 */
public final class ReturnTypeValidator {

    // --- Runtime type FQNs ---

    private static final String WORKFLOW_INSTANCE_ID_FQN = "dev.vertique.workflow.ops.WorkflowInstanceId";
    private static final String WORKFLOW_VIEW_FQN = "dev.vertique.workflow.ops.WorkflowView";
    private static final String FUTURE_FQN = "io.vertx.core.Future";

    private final CodegenContext ctx;

    /**
     * Constructs a validator bound to the given codegen context.
     *
     * @param ctx the codegen context; must not be {@code null}
     */
    public ReturnTypeValidator(CodegenContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Validates the return types of all clean single-role operations in the given contract model,
     * emitting compiler error diagnostics for every violation. Returns {@code true} when no errors
     * were found.
     *
     * @param model the scanned contract model to validate; must not be {@code null}
     * @return {@code true} if all checked operations have valid return types; {@code false} otherwise
     */
    public boolean validate(ContractModel model) {
        String fqn = model.contractType().getQualifiedName().toString();
        TypeElement instanceIdElement = ctx.elements().getTypeElement(WORKFLOW_INSTANCE_ID_FQN);
        TypeElement workflowViewElement = ctx.elements().getTypeElement(WORKFLOW_VIEW_FQN);

        boolean ok = true;
        for (OperationModel op : model.operations()) {
            // Only inspect clean single-role, non-default operations
            if (!op.isCleanSingleRoleOp()) {
                continue;
            }
            OperationRole role = op.role().get();
            TypeMirror rt = op.returnType();
            String m = op.methodName();

            switch (role) {
                case START -> ok &= validateStartReturn(fqn, m, rt, op, instanceIdElement);
                case SIGNAL -> ok &= validateSignalReturn(fqn, m, rt, op);
                case QUERY -> ok &= validateQueryReturn(fqn, m, rt, op, workflowViewElement);
            }
        }
        return ok;
    }

    // --- Role-specific return type checks ---

    /**
     * Validates that a {@code @WorkflowStart} method returns {@code Future<WorkflowInstanceId>}.
     *
     * @param fqn              the contract FQN for diagnostic messages
     * @param m                the method name
     * @param rt               the declared return type
     * @param op               the operation model (for attributing diagnostics)
     * @param instanceIdElement the {@code WorkflowInstanceId} type element, or {@code null}
     * @return {@code true} if the return type is valid
     */
    private boolean validateStartReturn(
            String fqn, String m, TypeMirror rt, OperationModel op, TypeElement instanceIdElement) {
        if (!isFuture(rt)) {
            ctx.diagnostics().error(op.method(), Diagnostics.workflowStartReturnType(fqn, m, rt.toString()));
            return false;
        }
        TypeMirror arg = ctx.unwrapFuture(rt);
        if (instanceIdElement == null || !isExactType(arg, instanceIdElement.asType())) {
            ctx.diagnostics().error(op.method(), Diagnostics.workflowStartReturnType(fqn, m, rt.toString()));
            return false;
        }
        return true;
    }

    /**
     * Validates that a {@code @WorkflowSignal} method returns {@code Future<Void>}.
     *
     * @param fqn the contract FQN for diagnostic messages
     * @param m   the method name
     * @param rt  the declared return type
     * @param op  the operation model (for attributing diagnostics)
     * @return {@code true} if the return type is valid
     */
    private boolean validateSignalReturn(String fqn, String m, TypeMirror rt, OperationModel op) {
        if (!isFuture(rt)) {
            ctx.diagnostics().error(op.method(), Diagnostics.workflowSignalReturnType(fqn, m, rt.toString()));
            return false;
        }
        TypeMirror arg = ctx.unwrapFuture(rt);
        if (!arg.toString().equals("java.lang.Void")) {
            ctx.diagnostics().error(op.method(), Diagnostics.workflowSignalReturnType(fqn, m, rt.toString()));
            return false;
        }
        return true;
    }

    /**
     * Validates that a {@code @WorkflowQuery} method returns exactly {@code Future<WorkflowView>}
     * (V1 single untyped query surface — no supertype or wildcard), matching the runtime
     * {@code WorkflowProxyValidator}.
     *
     * @param fqn                the contract FQN for diagnostic messages
     * @param m                  the method name
     * @param rt                 the declared return type
     * @param op                 the operation model (for attributing diagnostics)
     * @param workflowViewElement the {@code WorkflowView} type element, or {@code null}
     * @return {@code true} if the return type is valid
     */
    private boolean validateQueryReturn(
            String fqn, String m, TypeMirror rt, OperationModel op, TypeElement workflowViewElement) {
        if (workflowViewElement == null) {
            return true; // WorkflowView not on the classpath — skip (treat as pass)
        }
        if (!isFuture(rt) || !isExactType(ctx.unwrapFuture(rt), workflowViewElement.asType())) {
            ctx.diagnostics().error(op.method(), Diagnostics.workflowQueryReturnType(fqn, m, rt.toString()));
            return false;
        }
        return true;
    }

    // --- Type helpers ---

    /**
     * Returns {@code true} when the given type's erasure is {@code io.vertx.core.Future}.
     *
     * @param t the type to test; must not be {@code null}
     * @return {@code true} if the erased type is {@code Future}
     */
    private boolean isFuture(TypeMirror t) {
        return ctx.types().erasure(t).toString().equals(FUTURE_FQN);
    }

    /**
     * Returns {@code true} when {@code a} and {@code b} are the same type, compared <em>without</em>
     * erasure. The exact comparison is required so the start/query return-argument checks reject a
     * wildcard or parameterized argument (e.g. {@code Future<? extends WorkflowView>}) — an erased
     * comparison would collapse such an argument to its bound and accept it, making codegen looser than
     * the runtime {@code WorkflowProxyValidator} (whose {@code isFutureOf} requires the argument to be an
     * exact {@code Class}). The raw {@code Future} check ({@link #isFuture}) keeps using erasure.
     *
     * @param a the first type; must not be {@code null}
     * @param b the second type; must not be {@code null}
     * @return {@code true} if the types are exactly the same
     */
    private boolean isExactType(TypeMirror a, TypeMirror b) {
        return ctx.types().isSameType(a, b);
    }
}
