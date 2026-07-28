// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.services.processor.validate;

import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.services.processor.scan.OperationModel;
import dev.vertique.codegen.services.processor.scan.ParamModel;
import java.util.List;
import javax.lang.model.element.TypeElement;

/**
 * Validates that each contract method has at most one payload parameter.
 *
 * <p>Mirrors {@code dev.vertique.services.ParameterClassifier#classifyParams} (rule at
 * {@code ParameterClassifier.java:119-126}). The extraction phase already emits errors for
 * {@code DispatchEnvelope<?>} params and multiple payload params; this validator checks the
 * pre-extracted {@link OperationModel} list for any residual violations to satisfy the pipeline
 * contract.
 *
 * <p>In practice, extraction errors will cause the model to not be produced at all, so
 * this validator acts as a final safety guard on the built model.
 */
public final class PayloadParamValidator {

    private final CodegenContext ctx;

    /**
     * Constructs this validator bound to the given codegen context.
     *
     * @param ctx the shared codegen context; must not be {@code null}
     */
    public PayloadParamValidator(CodegenContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Validates that every operation has at most one payload parameter.
     *
     * @param contractType the {@code @ServiceContract} interface the operations belong to; used for
     *                     diagnostic messages; must not be {@code null}
     * @param operations   the extracted operations to validate; must not be {@code null}
     * @return {@code true} if all operations pass; {@code false} if any error was emitted
     */
    public boolean validate(TypeElement contractType, List<OperationModel> operations) {
        boolean valid = true;
        for (OperationModel op : operations) {
            long payloadCount =
                    op.params().stream().filter(ParamModel::isPayload).count();
            if (payloadCount > 1) {
                ctx.diagnostics()
                        .error(
                                op.contractMethod(),
                                "At most one payload parameter is allowed per method on %s.%s()"
                                        + ", found %d payload parameters",
                                contractType.getSimpleName(),
                                op.contractMethod().getSimpleName(),
                                payloadCount);
                valid = false;
            }
        }
        return valid;
    }
}
