// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.services.processor.validate;

import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.Diagnostics;
import dev.vertique.codegen.services.processor.ServiceAnnotations;
import dev.vertique.codegen.services.processor.scan.ContractModel;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

/**
 * Validates that every contract method returns a parameterized {@code Future<T>}.
 *
 * <p>Mirrors {@code dev.vertique.services.ReturnTypeResolver} (runtime check
 * {@code ReturnTypeResolver.java:26-63}).
 *
 * <p>Rejects:
 * <ul>
 *   <li>Non-{@code Future} return types (void, primitives, raw types).</li>
 *   <li>Raw (unparameterized) {@code Future}.</li>
 * </ul>
 */
public final class ReturnTypeValidator {

    private final CodegenContext ctx;

    /**
     * Constructs this validator bound to the given codegen context.
     *
     * @param ctx the shared codegen context; must not be {@code null}
     */
    public ReturnTypeValidator(CodegenContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Validates all contract methods in the given model.
     *
     * @param model the contract model to validate; must not be {@code null}
     * @return {@code true} if all methods pass; {@code false} if at least one error was emitted
     */
    public boolean validate(ContractModel model) {
        var futureElement = ctx.elements().getTypeElement(ServiceAnnotations.FUTURE);
        if (futureElement == null) {
            // io.vertx.core.Future not on classpath — skip
            return true;
        }
        TypeMirror futureErasure = ctx.types().erasure(futureElement.asType());

        boolean valid = true;
        for (var op : model.operations()) {
            ExecutableElement method = op.contractMethod();
            TypeMirror returnType = method.getReturnType();

            if (returnType.getKind() != TypeKind.DECLARED) {
                ctx.diagnostics()
                        .error(
                                method,
                                "%s",
                                Diagnostics.mustReturnFuture(
                                        model.contractType().getSimpleName() + "." + method.getSimpleName() + "()"));
                valid = false;
                continue;
            }

            DeclaredType declared = (DeclaredType) returnType;
            TypeMirror erasure = ctx.types().erasure(declared);
            if (!ctx.types().isSameType(erasure, futureErasure)) {
                ctx.diagnostics()
                        .error(
                                method,
                                "%s",
                                Diagnostics.mustReturnFuture(
                                        model.contractType().getSimpleName() + "." + method.getSimpleName() + "()"));
                valid = false;
                continue;
            }

            if (declared.getTypeArguments().isEmpty()) {
                ctx.diagnostics()
                        .error(
                                method,
                                "Return type Future on %s.%s() must be parameterized (e.g. Future<MyResponse>),"
                                        + " raw Future is not allowed",
                                model.contractType().getSimpleName(),
                                method.getSimpleName());
                valid = false;
            }
        }
        return valid;
    }
}
