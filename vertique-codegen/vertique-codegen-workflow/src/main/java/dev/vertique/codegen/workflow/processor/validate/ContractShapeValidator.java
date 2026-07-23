// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.workflow.processor.validate;

import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.Diagnostics;
import dev.vertique.codegen.support.MethodOverrides;
import dev.vertique.codegen.workflow.processor.scan.ContractModel;
import dev.vertique.codegen.workflow.processor.scan.OperationModel;
import dev.vertique.codegen.workflow.processor.scan.OperationRole;
import dev.vertique.codegen.workflow.processor.scan.ParamRole;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.lang.model.element.ElementKind;

/**
 * Compile-time structural shape validator for {@code @WorkflowContract} interfaces.
 *
 * <p>Enforces contract-level and per-method shape rules:
 * <ul>
 *   <li>The annotated type must be an interface.</li>
 *   <li>Exactly one {@code @WorkflowStart} method must be declared.</li>
 *   <li>Signal names must be unique across all clean {@code @WorkflowSignal} methods.</li>
 *   <li>No two operations may share an erased signature inherited from unrelated super-interfaces
 *       (an ambiguous sibling duplicate the concrete proxy cannot implement twice).</li>
 *   <li>Per method: default methods, conflicting roles, missing roles, and role-specific
 *       parameter cardinality and key-source rules are all checked.</li>
 * </ul>
 *
 * <p>All errors are emitted before returning so the developer receives the full diagnostic
 * picture in a single compilation cycle.
 */
public final class ContractShapeValidator {

    private final CodegenContext ctx;

    /**
     * Constructs a validator bound to the given codegen context.
     *
     * @param ctx the codegen context; must not be {@code null}
     */
    public ContractShapeValidator(CodegenContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Validates the structural shape of the given contract model, emitting compiler error
     * diagnostics for every violation found. Returns {@code true} when no errors were found.
     *
     * @param model the scanned contract model to validate; must not be {@code null}
     * @return {@code true} if the model is shape-valid; {@code false} if any error was emitted
     */
    public boolean validate(ContractModel model) {
        String fqn = model.contractType().getQualifiedName().toString();

        // --- Contract-level: must be an interface ---
        if (model.contractType().getKind() != ElementKind.INTERFACE) {
            ctx.diagnostics().error(model.contractType(), Diagnostics.workflowContractMustBeInterface(fqn));
            return false;
        }

        boolean ok = true;

        // --- Contract-level: exactly one @WorkflowStart ---
        long startCount = model.operations().stream()
                .filter(op -> op.declaredRoles().contains(OperationRole.START))
                .count();
        if (startCount != 1) {
            ctx.diagnostics()
                    .error(model.contractType(), Diagnostics.workflowStartMethodCardinality(fqn, (int) startCount));
            ok = false;
        }

        // --- Contract-level: duplicate signal names among clean SIGNAL ops ---
        Map<String, Integer> signalNameCount = new HashMap<>();
        for (OperationModel op : model.operations()) {
            if (op.role().isPresent() && op.role().get() == OperationRole.SIGNAL && !op.isDefault()) {
                String signalName = op.signalName();
                if (signalName != null) {
                    signalNameCount.merge(signalName, 1, Integer::sum);
                }
            }
        }
        for (Map.Entry<String, Integer> entry : signalNameCount.entrySet()) {
            if (entry.getValue() > 1) {
                ctx.diagnostics()
                        .error(model.contractType(), Diagnostics.workflowDuplicateSignalName(fqn, entry.getKey()));
                ok = false;
            }
        }

        // --- Contract-level: ambiguous sibling-inherited duplicate erased signatures ---
        // The scanner deduplicates via MethodOverrides, which already collapses override chains to the
        // most-derived declaration and same-enclosing-type duplicates. Any two operations that STILL share
        // an erased signature are therefore genuine siblings declared by unrelated super-interfaces. The
        // generated proxy is a concrete class and cannot override the same signature twice, and the reflective
        // runtime (WorkflowProxyValidator over Class#getMethods()) sees both declarations — reject the
        // ambiguous shape here so codegen and runtime agree rather than emitting a duplicate-method proxy.
        Map<String, Integer> erasedSignatureCount = new LinkedHashMap<>();
        for (OperationModel op : model.operations()) {
            erasedSignatureCount.merge(MethodOverrides.erasedSignature(op.method(), ctx.types()), 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> entry : erasedSignatureCount.entrySet()) {
            if (entry.getValue() > 1) {
                ctx.diagnostics()
                        .error(
                                model.contractType(),
                                Diagnostics.workflowDuplicateOperationSignature(fqn, entry.getKey()));
                ok = false;
            }
        }

        // --- Per-method checks ---
        for (OperationModel op : model.operations()) {
            String m = op.methodName();
            if (op.isDefault()) {
                ctx.diagnostics().error(op.method(), Diagnostics.workflowDefaultMethod(fqn, m));
                ok = false;
                continue;
            }
            if (op.declaredRoles().size() > 1) {
                ctx.diagnostics().error(op.method(), Diagnostics.workflowConflictingRoles(fqn, m));
                ok = false;
                continue;
            }
            if (op.declaredRoles().isEmpty()) {
                ctx.diagnostics().error(op.method(), Diagnostics.workflowNoRole(fqn, m));
                ok = false;
                continue;
            }

            // Clean single-role op — dispatch on role
            OperationRole role = op.role().get();
            switch (role) {
                case START -> ok &= validateStart(fqn, op);
                case SIGNAL -> ok &= validateSignal(fqn, op);
                case QUERY -> ok &= validateQuery(fqn, op);
            }
        }

        return ok;
    }

    // --- Role-specific per-method validators ---

    /**
     * Validates the shape rules for a {@code @WorkflowStart} method:
     * no INSTANCE_ID parameter, payload cardinality of exactly 1, and an idempotency key source.
     *
     * @param fqn the contract FQN for diagnostic messages
     * @param op  the operation model for the start method
     * @return {@code true} if no errors were emitted
     */
    private boolean validateStart(String fqn, OperationModel op) {
        String m = op.methodName();
        boolean ok = true;

        // @WorkflowStart must not have a WorkflowInstanceId parameter
        boolean hasInstanceId = op.params().stream().anyMatch(p -> p.role() == ParamRole.INSTANCE_ID);
        if (hasInstanceId) {
            ctx.diagnostics().error(op.method(), Diagnostics.workflowStartForbidsInstanceId(fqn, m));
            ok = false;
        }

        long pc = op.payloadParamCount();
        if (pc > 1) {
            ctx.diagnostics().error(op.method(), Diagnostics.workflowStartPayloadCardinality(fqn, m));
            ok = false;
        } else if (pc == 0) {
            ctx.diagnostics().error(op.method(), Diagnostics.workflowStartNoPayload(fqn, m));
            ok = false;
        } else {
            // Exactly one payload — check idempotency source
            boolean hasIdempotencyKeyParam = op.params().stream().anyMatch(p -> p.role() == ParamRole.IDEMPOTENCY_KEY);
            if (!hasIdempotencyKeyParam && !op.payloadIsIdempotencyKeyed()) {
                ctx.diagnostics().error(op.method(), Diagnostics.workflowMissingIdempotencySource(fqn, m));
                ok = false;
            }
        }

        return ok;
    }

    /**
     * Validates the shape rules for a {@code @WorkflowSignal} method:
     * exactly one INSTANCE_ID parameter (must be first), payload cardinality of exactly 1,
     * and a dedup key source.
     *
     * @param fqn the contract FQN for diagnostic messages
     * @param op  the operation model for the signal method
     * @return {@code true} if no errors were emitted
     */
    private boolean validateSignal(String fqn, OperationModel op) {
        String m = op.methodName();
        boolean ok = true;

        // Exactly one INSTANCE_ID parameter
        long instanceIdCount = op.params().stream()
                .filter(p -> p.role() == ParamRole.INSTANCE_ID)
                .count();
        if (instanceIdCount != 1) {
            ctx.diagnostics()
                    .error(op.method(), Diagnostics.workflowSignalInstanceIdCardinality(fqn, m, (int) instanceIdCount));
            ok = false;
        } else {
            // Exactly one — check it is first (index 0)
            int index = -1;
            for (int i = 0; i < op.params().size(); i++) {
                if (op.params().get(i).role() == ParamRole.INSTANCE_ID) {
                    index = i;
                    break;
                }
            }
            if (index != 0) {
                ctx.diagnostics().error(op.method(), Diagnostics.workflowSignalInstanceIdFirst(fqn, m, index));
                ok = false;
            }
        }

        long pc = op.payloadParamCount();
        if (pc > 1) {
            ctx.diagnostics().error(op.method(), Diagnostics.workflowSignalPayloadCardinality(fqn, m));
            ok = false;
        } else if (pc == 0) {
            ctx.diagnostics().error(op.method(), Diagnostics.workflowSignalNoPayload(fqn, m));
            ok = false;
        } else {
            // Exactly one payload — check dedup source
            boolean hasDedupKeyParam = op.params().stream().anyMatch(p -> p.role() == ParamRole.SIGNAL_DEDUP_KEY);
            if (!hasDedupKeyParam && !op.payloadIsSignalDedupKeyed()) {
                ctx.diagnostics().error(op.method(), Diagnostics.workflowMissingDedupSource(fqn, m));
                ok = false;
            }
        }

        return ok;
    }

    /**
     * Validates the shape rules for a {@code @WorkflowQuery} method:
     * exactly one parameter of role INSTANCE_ID.
     *
     * @param fqn the contract FQN for diagnostic messages
     * @param op  the operation model for the query method
     * @return {@code true} if no errors were emitted
     */
    private boolean validateQuery(String fqn, OperationModel op) {
        String m = op.methodName();
        boolean ok = true;

        int paramCount = op.params().size();
        boolean singleInstanceIdParam = paramCount == 1 && op.params().get(0).role() == ParamRole.INSTANCE_ID;
        if (!singleInstanceIdParam) {
            ctx.diagnostics().error(op.method(), Diagnostics.workflowQueryParam(fqn, m, paramCount));
            ok = false;
        }

        return ok;
    }
}
