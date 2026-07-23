// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.services.processor.validate;

import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.services.processor.scan.ContractModel;
import dev.vertique.codegen.services.processor.scan.ImplCandidate.ImplKind;
import java.util.HashMap;
import java.util.Map;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.ElementFilter;

/**
 * Validates that the handler class has no overloaded public method names (for HANDLER-pattern candidates).
 *
 * <p>Mirrors {@code dev.vertique.services.MethodValidator#validateHandlerMethods}
 * ({@code MethodValidator.java:80,106,191}).
 *
 * <p>Any duplicate-name <em>public</em> method on a handler class triggers rejection. No
 * "best overload" heuristic is attempted. This rule exists because the runtime resolves handler
 * methods by name only — two methods with the same name are ambiguous.
 *
 * <p>Only public methods are counted, matching the semantics of {@link Class#getMethods()}
 * used by the runtime {@code MethodValidator}. Private or package-private helpers that share a
 * name with a public operation method are excluded and do not trigger a false-positive rejection.
 *
 * <p>For {@link ImplKind#DIRECT} candidates this validator is a no-op (it only applies to
 * HANDLER-pattern implementations).
 */
public final class HandlerOverloadValidator {

    private final CodegenContext ctx;

    /**
     * Constructs this validator bound to the given codegen context.
     *
     * @param ctx the shared codegen context; must not be {@code null}
     */
    public HandlerOverloadValidator(CodegenContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Validates that the handler class has no overloaded method names.
     *
     * <p>Returns {@code true} immediately for {@link ImplKind#DIRECT} candidates.
     *
     * @param model the contract model to validate; must not be {@code null}
     * @return {@code true} if no overloads found (or DIRECT); {@code false} if any error was emitted
     */
    public boolean validate(ContractModel model) {
        if (model.kind() != ImplKind.HANDLER) {
            return true;
        }

        TypeElement handlerType = model.implType();
        boolean valid = true;

        // Filter to public methods, matching Class.getMethods() semantics used by the runtime
        // MethodValidator. Private/package-private helpers are excluded so that a private method
        // sharing a name with a public operation does not trigger a false-positive overload error.
        Map<String, Integer> counts = new HashMap<>();
        for (ExecutableElement m : ElementFilter.methodsIn(ctx.elements().getAllMembers(handlerType))) {
            if (!m.getModifiers().contains(Modifier.PUBLIC)) {
                continue;
            }
            if (m.getKind() != ElementKind.METHOD) {
                continue;
            }
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
                                handlerType,
                                "%s has %d overloaded methods named '%s' — exactly one handler method per"
                                        + " operation is required",
                                handlerType.getSimpleName(),
                                entry.getValue(),
                                entry.getKey());
                valid = false;
            }
        }

        return valid;
    }
}
