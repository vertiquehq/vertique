// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.services.processor.validate;

import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.Diagnostics;
import dev.vertique.codegen.services.processor.scan.OperationModel;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.lang.model.element.TypeElement;

/**
 * Validates that no two operations within a contract resolve to the same operation name.
 *
 * <p>Mirrors duplicate-operation detection in {@code dev.vertique.services.ServiceRegistrar}
 * ({@code ServiceRegistrar.java:159-170}).
 *
 * <p>Operation names are the runtime event bus address segment. Two methods resolving to the same
 * name would collide on the event bus and cause one to silently shadow the other.
 */
public final class OperationCollisionValidator {

    private final CodegenContext ctx;

    /**
     * Constructs this validator bound to the given codegen context.
     *
     * @param ctx the shared codegen context; must not be {@code null}
     */
    public OperationCollisionValidator(CodegenContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Validates that no two of the given operations have the same resolved operation name.
     *
     * @param contractType the {@code @ServiceContract} interface the operations belong to; accepted
     *                     for signature symmetry with the other contract-shape validators — the
     *                     collision diagnostic is anchored on the offending method element rather
     *                     than the contract; must not be {@code null}
     * @param operations   the extracted operations to validate; must not be {@code null}
     * @return {@code true} if no collisions found; {@code false} if any error was emitted
     */
    public boolean validate(TypeElement contractType, List<OperationModel> operations) {
        boolean valid = true;
        Map<String, String> seenNames = new HashMap<>();

        for (var op : operations) {
            String opName = op.operationName();
            String previous =
                    seenNames.put(opName, op.contractMethod().getSimpleName().toString());
            if (previous != null) {
                ctx.diagnostics()
                        .error(
                                op.contractMethod(),
                                "%s: both %s and %s resolve to operation '%s'",
                                Diagnostics.duplicateOperation(opName),
                                previous,
                                op.contractMethod().getSimpleName(),
                                opName);
                valid = false;
            }
        }

        return valid;
    }
}
