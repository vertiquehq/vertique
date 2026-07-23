// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.services.processor.validate;

import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.Diagnostics;
import dev.vertique.codegen.services.processor.scan.ContractModel;
import java.util.HashMap;
import java.util.Map;

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
     * Validates that no two operations in the model have the same resolved operation name.
     *
     * @param model the contract model to validate; must not be {@code null}
     * @return {@code true} if no collisions found; {@code false} if any error was emitted
     */
    public boolean validate(ContractModel model) {
        boolean valid = true;
        Map<String, String> seenNames = new HashMap<>();

        for (var op : model.operations()) {
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
