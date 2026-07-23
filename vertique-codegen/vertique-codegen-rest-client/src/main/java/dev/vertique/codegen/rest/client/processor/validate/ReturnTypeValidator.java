// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.rest.client.processor.validate;

import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.Diagnostics;
import dev.vertique.codegen.rest.client.processor.MethodModel;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

/**
 * Validates that every method in a {@code @RestClient} interface returns {@code Future<T>}.
 *
 * <p>A method's return type is considered valid when the erased type resolves to
 * {@code io.vertx.core.Future}. If it does not, a compiler error is emitted via
 * {@link Diagnostics#mustReturnFuture(String)}.
 */
public final class ReturnTypeValidator {

    private static final String FUTURE_FQN = "io.vertx.core.Future";

    private final CodegenContext ctx;

    /**
     * Creates a new validator bound to the given codegen context.
     *
     * @param ctx the shared codegen context; must not be {@code null}
     */
    public ReturnTypeValidator(CodegenContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Validates that the method's return type is {@code Future<T>} or {@code Future<Void>}.
     * Emits a compiler error on failure.
     *
     * @param method the method model to validate
     * @return {@code true} when the return type is a {@code Future}; {@code false} on error
     */
    public boolean validate(MethodModel method) {
        TypeMirror returnType = method.returnType();
        if (isFutureType(returnType)) {
            return true;
        }
        String methodName = method.method().getSimpleName().toString();
        ctx.diagnostics().error(method.method(), Diagnostics.mustReturnFuture(methodName));
        return false;
    }

    // --- Internal helpers ---

    /**
     * Returns {@code true} when the given type mirror's erasure is {@code io.vertx.core.Future}.
     *
     * @param type the type to inspect
     * @return {@code true} if the erased type is {@code Future}
     */
    private boolean isFutureType(TypeMirror type) {
        if (type.getKind() == TypeKind.VOID || type.getKind().isPrimitive()) {
            return false;
        }
        TypeMirror erased = ctx.types().erasure(type);
        return erased.toString().equals(FUTURE_FQN);
    }
}
