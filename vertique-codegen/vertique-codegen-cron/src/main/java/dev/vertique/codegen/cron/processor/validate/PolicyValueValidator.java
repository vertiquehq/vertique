// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.cron.processor.validate;

import dev.vertique.codegen.CodegenContext;
import java.time.DateTimeException;
import java.time.ZoneId;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.VariableElement;

/**
 * Validates policy-bound attributes of a {@code @CronJob}-annotated method at compile time.
 *
 * <p>Validates:
 * <ul>
 *   <li>{@code id} — must not be blank</li>
 *   <li>{@code maxAttempts} — must be {@code >= 1}</li>
 *   <li>{@code overlapPolicy} / {@code mode} — {@code QUEUE_ONE} requires {@code EVERY_INSTANCE}</li>
 *   <li>{@code timezone} — emits a {@code WARNING} (not error) when the value cannot be resolved
 *       via {@link ZoneId#of(String)}, per FR-CG004-007 (external config may override)</li>
 * </ul>
 */
public final class PolicyValueValidator {

    private final CodegenContext ctx;

    /**
     * Creates a new validator bound to the given codegen context.
     *
     * @param ctx the shared codegen context; must not be {@code null}
     */
    public PolicyValueValidator(CodegenContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Validates policy-bound attributes against an already-resolved annotation mirror.
     *
     * <p>Each violation is emitted independently so all problems surface in a single compilation.
     *
     * @param method the {@code @CronJob}-annotated method (used for diagnostic positioning)
     * @param mirror the {@code @CronJob} annotation mirror, pre-resolved by the processor
     */
    public void validate(ExecutableElement method, AnnotationMirror mirror) {
        validateId(method, mirror);
        validateMaxAttempts(method, mirror);
        validateOverlapPolicy(method, mirror);
        validateTimezone(method, mirror);
    }

    private void validateId(ExecutableElement method, AnnotationMirror mirror) {
        ctx.annotations().attribute(mirror, "id", String.class).ifPresent(id -> {
            if (id.isBlank()) {
                ctx.diagnostics().error(method, "@CronJob.id must not be blank");
            }
        });
    }

    private void validateMaxAttempts(ExecutableElement method, AnnotationMirror mirror) {
        ctx.annotations().attribute(mirror, "maxAttempts", Integer.class).ifPresent(v -> {
            if (v < 1) {
                ctx.diagnostics().error(method, "@CronJob.maxAttempts must be >= 1, got %d", v);
            }
        });
    }

    private void validateOverlapPolicy(ExecutableElement method, AnnotationMirror mirror) {
        String mode = enumName(mirror, "mode");
        String overlapPolicy = enumName(mirror, "overlapPolicy");
        if ("SINGLE_INSTANCE".equals(mode) && "QUEUE_ONE".equals(overlapPolicy)) {
            ctx.diagnostics()
                    .error(
                            method,
                            "@CronJob.overlapPolicy=QUEUE_ONE is only valid with mode=EVERY_INSTANCE;"
                                    + " SINGLE_INSTANCE requires SKIP");
        }
    }

    private void validateTimezone(ExecutableElement method, AnnotationMirror mirror) {
        ctx.annotations().attribute(mirror, "timezone", String.class).ifPresent(tz -> {
            try {
                ZoneId.of(tz);
            } catch (DateTimeException | IllegalArgumentException ex) {
                ctx.diagnostics()
                        .warning(
                                method,
                                "@CronJob.timezone '%s' did not resolve as a Java ZoneId;"
                                        + " will be validated at runtime (config override may apply)",
                                tz);
            }
        });
    }

    private String enumName(AnnotationMirror mirror, String attribute) {
        return ctx.annotations()
                .attribute(mirror, attribute, VariableElement.class)
                .map(v -> v.getSimpleName().toString())
                .orElse(null);
    }
}
