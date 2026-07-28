// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.services.processor.validate;

import dev.vertique.codegen.CodegenContext;
import java.util.HashMap;
import java.util.Map;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.ElementFilter;

/**
 * Validates that the contract interface has no overloaded method names.
 *
 * <p>Mirrors {@code dev.vertique.services.MethodValidator#detectOverloads}
 * ({@code MethodValidator.java:27-41}).
 *
 * <p>Service contracts require a unique method name per operation because operation names are
 * derived from method names (when {@code @ServiceOperation} is absent). Two methods with the
 * same name would produce the same operation name and therefore the same event bus address,
 * causing a runtime address collision.
 */
public final class ContractOverloadValidator {

    private final CodegenContext ctx;

    /**
     * Constructs this validator bound to the given codegen context.
     *
     * @param ctx the shared codegen context; must not be {@code null}
     */
    public ContractOverloadValidator(CodegenContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Validates that no two methods on the contract interface share the same name.
     *
     * <p>Rescans the contract's members directly, so it needs no extracted operation list.
     *
     * @param contractType the {@code @ServiceContract} interface to validate; must not be
     *                     {@code null}
     * @return {@code true} if no overloads found; {@code false} if any error was emitted
     */
    public boolean validate(TypeElement contractType) {
        boolean valid = true;

        Map<String, Integer> counts = new HashMap<>();
        for (ExecutableElement m : ElementFilter.methodsIn(ctx.elements().getAllMembers(contractType))) {
            if (m.getEnclosingElement() instanceof TypeElement owner) {
                if ("java.lang.Object".equals(owner.getQualifiedName().toString())) {
                    continue;
                }
            }
            counts.merge(m.getSimpleName().toString(), 1, Integer::sum);
        }

        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > 1) {
                ctx.diagnostics()
                        .error(
                                contractType,
                                "Overloaded methods are not allowed on @ServiceContract interface %s:"
                                        + " method '%s' appears %d times",
                                contractType.getSimpleName(),
                                entry.getKey(),
                                entry.getValue());
                valid = false;
            }
        }

        return valid;
    }
}
