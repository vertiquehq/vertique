// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.services.processor.validate;

import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.services.processor.scan.ContractModel;
import dev.vertique.codegen.services.processor.scan.ImplCandidate.ImplKind;
import dev.vertique.codegen.services.processor.scan.OperationModel;
import dev.vertique.codegen.services.processor.scan.ParamModel;
import java.util.List;
import javax.lang.model.type.TypeMirror;

/**
 * Validates that handler method signatures match their contract method counterparts.
 *
 * <p>Mirrors {@code dev.vertique.services.MethodValidator#validateHandlerMethods}
 * ({@code MethodValidator.java:54-160}).
 *
 * <p>Rules (for {@link ImplKind#HANDLER} only):
 * <ul>
 *   <li>Payload parameters must be identical in order and type between contract and handler.</li>
 *   <li>Handler may declare additional {@code DISPATCH_CONTEXT} parameters beyond the payload
 *       params; these must be {@code SecurityContext} subtypes or {@code @DispatchContextValue}
 *       types.</li>
 *   <li>Any handler parameter that exceeds the expected payload count and is <em>not</em>
 *       classified as {@code DISPATCH_CONTEXT} (i.e., not a {@code SecurityContext} subtype
 *       and not annotated with {@code @DispatchContextValue}) gets a targeted diagnostic
 *       naming the parameter and suggesting the two valid kinds.</li>
 *   <li>Handler return type must match the contract return type exactly ({@code Future<T>}
 *       where {@code T} is the same).</li>
 * </ul>
 */
public final class HandlerMatchValidator {

    private final CodegenContext ctx;

    /**
     * Constructs this validator bound to the given codegen context.
     *
     * @param ctx the shared codegen context; must not be {@code null}
     */
    public HandlerMatchValidator(CodegenContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Validates handler method match for all operations in the model.
     *
     * <p>Returns {@code true} immediately for {@link ImplKind#DIRECT} candidates.
     *
     * @param model the contract model to validate; must not be {@code null}
     * @return {@code true} if all operations pass; {@code false} if any error was emitted
     */
    public boolean validate(ContractModel model) {
        if (model.kind() != ImplKind.HANDLER) {
            return true;
        }

        boolean valid = true;

        for (OperationModel op : model.operations()) {
            // Validate payload params identical in order and type
            List<ParamModel> contractPayloads =
                    op.params().stream().filter(ParamModel::isPayload).toList();
            List<ParamModel> handlerPayloads =
                    op.handlerParams().stream().filter(ParamModel::isPayload).toList();

            if (!payloadsMatch(contractPayloads, handlerPayloads)) {
                ctx.diagnostics()
                        .error(
                                op.handlerMethod(),
                                "Handler method %s.%s() payload parameters do not match contract %s.%s():"
                                        + " expected %d payload param(s) but found %d",
                                model.implType().getSimpleName(),
                                op.handlerMethod().getSimpleName(),
                                model.contractType().getSimpleName(),
                                op.contractMethod().getSimpleName(),
                                contractPayloads.size(),
                                handlerPayloads.size());
                valid = false;
            }

            // Emit targeted diagnostics for extra handler params that are neither SecurityContext
            // nor @DispatchContextValue-annotated. These appear in handlerParams() as PAYLOAD
            // entries beyond the contract's payload count — the user likely forgot one of the
            // two valid dispatch-context annotations.
            List<ParamModel> unrecognisedExtras = findUnrecognisedExtras(contractPayloads, op.handlerParams());
            for (ParamModel extra : unrecognisedExtras) {
                ctx.diagnostics()
                        .error(
                                op.handlerMethod(),
                                "Handler method %s.%s() has extra parameter '%s' of type %s"
                                        + " that is neither a SecurityContext subtype nor annotated with"
                                        + " @DispatchContextValue — extra handler parameters must be one of"
                                        + " these two valid kinds",
                                model.implType().getSimpleName(),
                                op.handlerMethod().getSimpleName(),
                                extra.name(),
                                extra.type());
                valid = false;
            }

            // Validate return type matches (Future<T> T must match)
            TypeMirror contractReturn = op.contractMethod().getReturnType();
            TypeMirror handlerReturn = op.handlerMethod().getReturnType();

            if (!ctx.types().isSameType(contractReturn, handlerReturn)) {
                ctx.diagnostics()
                        .error(
                                op.handlerMethod(),
                                "Handler method %s.%s() return type mismatch: contract declares %s but handler"
                                        + " declares %s",
                                model.implType().getSimpleName(),
                                op.handlerMethod().getSimpleName(),
                                contractReturn,
                                handlerReturn);
                valid = false;
            }
        }

        return valid;
    }

    // --- Internal helpers ---

    /**
     * Returns {@code true} if the two payload parameter lists match in size and types (by erasure).
     *
     * @param contractPayloads payload params from the contract method
     * @param handlerPayloads  payload params from the handler method
     * @return {@code true} if they match
     */
    private boolean payloadsMatch(List<ParamModel> contractPayloads, List<ParamModel> handlerPayloads) {
        if (contractPayloads.size() != handlerPayloads.size()) {
            return false;
        }
        for (int i = 0; i < contractPayloads.size(); i++) {
            TypeMirror contractType = contractPayloads.get(i).type();
            TypeMirror handlerType = handlerPayloads.get(i).type();
            if (!ctx.types()
                    .isSameType(ctx.types().erasure(contractType), ctx.types().erasure(handlerType))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the handler params that are classified as {@code PAYLOAD} but exceed the
     * contract's payload count.
     *
     * <p>These are the "extra" params that users intended to be dispatch-context params
     * but forgot to annotate with {@code @DispatchContextValue} or use a {@code SecurityContext}
     * subtype. The AptParamClassifier already classified them as {@code PAYLOAD} because they
     * matched neither of the two valid dispatch-context shapes.
     *
     * @param contractPayloads the contract method's payload params
     * @param handlerParams    all params of the handler method (PAYLOAD + DISPATCH_CONTEXT)
     * @return the extra PAYLOAD params beyond the contract's expected count; never {@code null}
     */
    private List<ParamModel> findUnrecognisedExtras(List<ParamModel> contractPayloads, List<ParamModel> handlerParams) {
        List<ParamModel> handlerPayloads =
                handlerParams.stream().filter(ParamModel::isPayload).toList();
        if (handlerPayloads.size() <= contractPayloads.size()) {
            return List.of();
        }
        // The extras are the handler payload params beyond what the contract expects
        return handlerPayloads.subList(contractPayloads.size(), handlerPayloads.size());
    }
}
