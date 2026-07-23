// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.validate;

import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.Constructors;
import dev.vertique.codegen.Diagnostics;
import java.util.List;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;

/**
 * Validates that a type is bound by exactly one {@code @Inject}-annotated constructor.
 *
 * <p>This is the shared binding-origin check for the codegen series: a generated Dagger
 * {@code @Binds}/substitution may only target a type that Dagger itself would construct via
 * constructor injection. Generating a binding for a type with no {@code @Inject} constructor would
 * fabricate a binding that did not exist; generating one for a type with several {@code @Inject}
 * constructors is ambiguous. Both are hard compile errors.
 *
 * <p>Rules:
 * <ul>
 *   <li>0 {@code @Inject} constructors → ERROR (message contains {@code "no @Inject constructor"})</li>
 *   <li>1 {@code @Inject} constructor → OK</li>
 *   <li>{@code >1} {@code @Inject} constructors → ERROR
 *       ({@link Diagnostics#duplicateInjectConstructor}, message contains
 *       {@code "multiple @Inject constructors"})</li>
 * </ul>
 *
 * <p>Stricter than CG-002's
 * {@code dev.vertique.codegen.dagger.processor.support.Filters#validateSingleInjectConstructor},
 * which silently skips on zero constructors.
 */
public final class InjectConstructorValidator {

    private final CodegenContext ctx;

    /**
     * Constructs this validator bound to the given codegen context.
     *
     * @param ctx the shared codegen context; must not be {@code null}
     */
    public InjectConstructorValidator(CodegenContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Validates that the given type has exactly one {@code @Inject} constructor, emitting a hard
     * compile error otherwise.
     *
     * @param type the type whose constructors are checked; must not be {@code null}
     * @return {@code true} if exactly one {@code @Inject} constructor is found; {@code false} if any
     *     error was emitted
     */
    public boolean validate(TypeElement type) {
        List<ExecutableElement> injectCtors = Constructors.findInjectConstructors(type);

        if (injectCtors.isEmpty()) {
            ctx.diagnostics()
                    .error(
                            type,
                            "%s has no @Inject constructor — codegen requires exactly one @Inject"
                                    + " constructor on the bound type",
                            type.getQualifiedName());
            return false;
        }

        if (injectCtors.size() > 1) {
            ctx.diagnostics()
                    .error(
                            type,
                            "%s",
                            Diagnostics.duplicateInjectConstructor(
                                    type.getQualifiedName().toString()));
            return false;
        }

        return true;
    }
}
