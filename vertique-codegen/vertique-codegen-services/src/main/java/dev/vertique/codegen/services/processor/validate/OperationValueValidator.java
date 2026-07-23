// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.services.processor.validate;

import dev.vertique.codegen.AnnotationMirrors;
import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.services.processor.ServiceAnnotations;
import dev.vertique.codegen.services.processor.scan.ContractModel;
import javax.lang.model.element.ExecutableElement;

/**
 * Validates that every {@code @ServiceOperation} annotation on a contract method has a non-blank
 * {@code value()}.
 *
 * <p>Mirrors {@code dev.vertique.services.OperationIdResolver#resolveStableOperationId}
 * ({@code OperationIdResolver.java:62-67}).
 *
 * <p>A blank value is forbidden because it would produce an empty or whitespace-only operation id,
 * causing address and stable-target-id construction to behave unexpectedly.
 */
public final class OperationValueValidator {

    private final CodegenContext ctx;

    /**
     * Constructs this validator bound to the given codegen context.
     *
     * @param ctx the shared codegen context; must not be {@code null}
     */
    public OperationValueValidator(CodegenContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Validates that all {@code @ServiceOperation} annotations in the model have non-blank values.
     *
     * @param model the contract model to validate; must not be {@code null}
     * @return {@code true} if all pass; {@code false} if any error was emitted
     */
    public boolean validate(ContractModel model) {
        boolean valid = true;
        for (var op : model.operations()) {
            ExecutableElement method = op.contractMethod();
            var mirror = AnnotationMirrors.findByFqn(method, ServiceAnnotations.SERVICE_OPERATION);
            if (mirror.isEmpty()) {
                continue;
            }
            var value = ctx.annotations().attribute(mirror.get(), "value", String.class);
            if (value.isPresent() && value.get().isBlank()) {
                ctx.diagnostics()
                        .error(
                                method,
                                "@ServiceOperation on %s.%s() has a blank value — the operation id must be non-blank",
                                model.contractType().getSimpleName(),
                                method.getSimpleName());
                valid = false;
            }
        }
        return valid;
    }
}
