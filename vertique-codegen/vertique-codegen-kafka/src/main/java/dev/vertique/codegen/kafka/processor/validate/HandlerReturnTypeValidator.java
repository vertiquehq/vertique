// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.kafka.processor.validate;

import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.Diagnostics;
import dev.vertique.codegen.kafka.processor.scan.ListenerModel;
import dev.vertique.codegen.kafka.processor.scan.RouteModel;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

/**
 * Validates that each {@code @KafkaHandler} method returns {@code void} or
 * {@code Future<Void>} (FR-CG006-005).
 *
 * <p>The return type check uses {@link CodegenContext#unwrapFuture} to strip the
 * {@code Future<X>} wrapper: after unwrapping, only {@code Void} (the boxed type) or the
 * {@link TypeKind#VOID void} primitive are accepted. Any other return type is rejected with
 * {@link Diagnostics#kafkaHandlerReturnType}.
 *
 * <p>This validator is a no-op for Model 4 binding listeners (they have no routes).
 */
public final class HandlerReturnTypeValidator {

    private static final String VOID_FQN = "java.lang.Void";

    private final CodegenContext ctx;

    /**
     * Constructs the validator bound to the given codegen context.
     *
     * @param ctx the shared codegen context; must not be {@code null}
     */
    public HandlerReturnTypeValidator(CodegenContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Validates the return type of every route in the listener model.
     *
     * @param model the scanned listener model to validate; must not be {@code null}
     * @return {@code true} when all routes are valid; {@code false} when at least one diagnostic
     *         was emitted
     */
    public boolean validate(ListenerModel model) {
        if (model.kind() != ListenerModel.Kind.ROUTER) {
            return true; // Model 4 direct handlers have no @KafkaHandler routes to validate.
        }

        String listenerName = model.originType().getSimpleName().toString();
        boolean allOk = true;

        for (RouteModel route : model.routes()) {
            if (!isValidReturnType(route)) {
                ctx.diagnostics()
                        .error(
                                route.method(),
                                Diagnostics.kafkaHandlerReturnType(
                                        listenerName,
                                        route.method().getSimpleName().toString()));
                allOk = false;
            }
        }
        return allOk;
    }

    // --- Internal helpers ---

    /**
     * Returns {@code true} when the route's return type is {@code void} or {@code Future<Void>}.
     *
     * @param route the route to check
     * @return {@code true} when the return type is valid
     */
    private boolean isValidReturnType(RouteModel route) {
        TypeMirror returnType = route.method().getReturnType();

        // Plain void
        if (returnType.getKind() == TypeKind.VOID) {
            return true;
        }

        // Future<Void> — unwrap the Future wrapper and check for java.lang.Void
        TypeMirror inner = ctx.unwrapFuture(returnType);
        if (inner == returnType) {
            // unwrapFuture returned the same object — not a Future at all
            return false;
        }
        return isBoxedVoid(inner);
    }

    /**
     * Returns {@code true} when the type mirror represents {@code java.lang.Void}.
     *
     * @param type the type mirror to check
     * @return {@code true} when the type is {@code java.lang.Void}
     */
    private boolean isBoxedVoid(TypeMirror type) {
        return ctx.asTypeElement(type)
                .map(te -> VOID_FQN.equals(te.getQualifiedName().toString()))
                .orElse(false);
    }
}
