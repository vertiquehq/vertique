// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.cron.processor.validate;

import dev.vertique.codegen.CodegenContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;

/**
 * Validates that no two {@code @CronJob}-annotated methods on the same class share the same
 * {@code id} value.
 *
 * <p>Groups the annotated methods by their {@code id} attribute and emits an error on every
 * duplicate (second and subsequent occurrences). Methods with blank or missing ids are skipped
 * here because they are already caught by {@link PolicyValueValidator}.
 *
 * <p>Cross-class duplicate detection is out of scope (PRD non-goal — would require Maven plugin
 * territory visibility across compilation units).
 */
public final class DuplicateIdValidator {

    private final CodegenContext ctx;

    /**
     * Creates a new validator bound to the given codegen context.
     *
     * @param ctx the shared codegen context; must not be {@code null}
     */
    public DuplicateIdValidator(CodegenContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Validates within-class {@code @CronJob} id uniqueness.
     *
     * <p>Groups the given (method, mirror) pairs by their {@code id} attribute. For any group with
     * more than one method, emits an error on every method after the first, identifying the first
     * declaration in the message.
     *
     * @param methodMirrors all {@code @CronJob}-annotated methods on a single owner, paired with
     *                      their pre-resolved annotation mirrors; must not be {@code null}
     */
    public void validate(List<MethodMirror> methodMirrors) {
        if (methodMirrors.isEmpty()) {
            return;
        }
        TypeElement owner = (TypeElement) methodMirrors.get(0).method().getEnclosingElement();
        String ownerFqn = owner.getQualifiedName().toString();

        Map<String, List<ExecutableElement>> byId = new LinkedHashMap<>();
        for (MethodMirror mm : methodMirrors) {
            String id =
                    ctx.annotations().attribute(mm.mirror(), "id", String.class).orElse(null);
            if (id == null || id.isBlank()) {
                // Already errored by PolicyValueValidator; skip duplicate detection for blank ids.
                continue;
            }
            byId.computeIfAbsent(id, k -> new ArrayList<>()).add(mm.method());
        }

        for (Map.Entry<String, List<ExecutableElement>> entry : byId.entrySet()) {
            List<ExecutableElement> group = entry.getValue();
            if (group.size() <= 1) {
                continue;
            }
            String firstMethodName = group.get(0).getSimpleName().toString();
            for (int i = 1; i < group.size(); i++) {
                ctx.diagnostics()
                        .error(
                                group.get(i),
                                "@CronJob.id '%s' is duplicated; a previous declaration on %s.%s()"
                                        + " already uses this id",
                                entry.getKey(),
                                ownerFqn,
                                firstMethodName);
            }
        }
    }

    /**
     * Carrier pairing a {@code @CronJob}-annotated method with its pre-resolved annotation mirror,
     * so each method's mirror is read once and reused across validators.
     *
     * @param method the annotated method
     * @param mirror the resolved {@code @CronJob} annotation mirror
     */
    public record MethodMirror(ExecutableElement method, AnnotationMirror mirror) {}
}
