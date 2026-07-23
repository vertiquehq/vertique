// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.cron.processor.validate;

import dev.vertique.codegen.CodegenContext;
import dev.vertique.job.cron.CronExpression;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.ExecutableElement;

/**
 * Validates the {@code cron} attribute of a {@code @CronJob}-annotated method at compile time.
 *
 * <p>Calls {@code new CronExpression(expr)} inside a {@code try/catch} to reuse the runtime
 * parser's validation logic. On {@link IllegalArgumentException}, the parser's exact message is
 * forwarded as a compiler {@code ERROR} (prefixed for context) so users see the same error text
 * regardless of whether the problem surfaces at build time or runtime.
 */
public final class CronExpressionValidator {

    private final CodegenContext ctx;

    /**
     * Creates a new validator bound to the given codegen context.
     *
     * @param ctx the shared codegen context; must not be {@code null}
     */
    public CronExpressionValidator(CodegenContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Validates the {@code cron} expression on the given method using the supplied mirror.
     *
     * @param method the {@code @CronJob}-annotated method (used for diagnostic positioning)
     * @param mirror the {@code @CronJob} annotation mirror, pre-resolved by the processor
     */
    public void validate(ExecutableElement method, AnnotationMirror mirror) {
        ctx.annotations().attribute(mirror, "cron", String.class).ifPresent(cron -> {
            try {
                new CronExpression(cron);
            } catch (IllegalArgumentException ex) {
                ctx.diagnostics().error(method, "@CronJob.cron: %s", ex.getMessage());
            }
        });
    }
}
