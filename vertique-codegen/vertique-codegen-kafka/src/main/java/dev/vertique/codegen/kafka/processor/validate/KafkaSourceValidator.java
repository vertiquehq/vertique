// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.kafka.processor.validate;

import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.Diagnostics;
import dev.vertique.codegen.kafka.processor.scan.KafkaSourceMethodModel;
import dev.vertique.codegen.kafka.processor.scan.KafkaSourceModel;
import java.util.ArrayList;
import java.util.List;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;

/**
 * Validates compile-time constraints for {@link dev.vertique.kafka.KafkaSource @KafkaSource}
 * methods on service-implementation classes (CG-012 Track A, Slice 5).
 *
 * <p>Validation rules:
 * <ol>
 *   <li><b>Interface guard</b> — if {@code @KafkaSource} is placed on a method of an interface
 *       type, emit {@link Diagnostics#kafkaSourceOnInterface(String, String)} for each such
 *       method. The entire interface element is skipped; no companion is emitted for it.</li>
 *   <li><b>Blank topic</b> — each annotated method must supply a non-blank {@code topic()};
 *       blank-topic methods are removed from the valid set and an error is emitted.</li>
 *   <li><b>Blank groupId</b> — each annotated method must supply a non-blank {@code groupId()};
 *       blank-groupId methods are removed from the valid set and an error is emitted.</li>
 * </ol>
 *
 * <p>An impl class whose valid-method list becomes empty after per-method validation is dropped
 * entirely from the returned list (no companion is emitted for it).
 *
 * <p>All validators run for each method before returning (bitwise {@code &}), so all errors are
 * surfaced in a single compilation round.
 */
public final class KafkaSourceValidator {

    private final CodegenContext ctx;

    /**
     * Constructs the validator bound to the given codegen context.
     *
     * @param ctx the shared codegen context; must not be {@code null}
     */
    public KafkaSourceValidator(CodegenContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Validates a list of raw {@link KafkaSourceModel} instances, emitting diagnostics for
     * violations and returning only the models (with only the valid methods) that should proceed
     * to emission.
     *
     * <p>Models produced from interface elements are rejected entirely (rule 1). For class-element
     * models, only the per-method subset that passes rules 2 and 3 is retained; if that subset is
     * empty the model is dropped.
     *
     * @param models the raw models produced by
     *               {@link dev.vertique.codegen.kafka.processor.scan.KafkaSourceScanner}; must not
     *               be {@code null}
     * @return the subset of models that passed all checks, with per-method sub-filtering applied;
     *         never {@code null}
     */
    public List<KafkaSourceModel> validate(List<KafkaSourceModel> models) {
        List<KafkaSourceModel> valid = new ArrayList<>();
        for (KafkaSourceModel model : models) {
            if (model.implType().getKind() == ElementKind.INTERFACE) {
                // Rule 1: @KafkaSource must not appear on interface methods; emit per-method errors
                for (KafkaSourceMethodModel m : model.methods()) {
                    ctx.diagnostics()
                            .error(
                                    m.method(),
                                    Diagnostics.kafkaSourceOnInterface(
                                            model.implType().getSimpleName().toString(), m.targetOperation()));
                }
                continue;
            }

            List<KafkaSourceMethodModel> validMethods = validateMethods(model);
            if (!validMethods.isEmpty()) {
                valid.add(new KafkaSourceModel(model.implType(), validMethods));
            }
        }
        return valid;
    }

    // --- Internal helpers ---

    /**
     * Validates each method in the model against the blank-topic and blank-groupId rules.
     *
     * <p>All checks run for each method regardless of earlier failures (bitwise {@code &}), so
     * that all diagnostics surface in a single compilation round.
     *
     * @param model the model whose methods are to be validated
     * @return the subset of methods that passed all per-method checks
     */
    private List<KafkaSourceMethodModel> validateMethods(KafkaSourceModel model) {
        TypeElement implType = model.implType();
        String className = implType.getSimpleName().toString();
        List<KafkaSourceMethodModel> validMethods = new ArrayList<>();

        for (KafkaSourceMethodModel m : model.methods()) {
            boolean ok = validateTopic(m, className) & validateGroupId(m, className);
            if (ok) {
                validMethods.add(m);
            }
        }
        return validMethods;
    }

    /**
     * Validates that the method's {@code topic()} is non-blank.
     *
     * @param m         the method model to check
     * @param className the simple name of the enclosing impl class (for the diagnostic message)
     * @return {@code true} when valid, {@code false} when a diagnostic was emitted
     */
    private boolean validateTopic(KafkaSourceMethodModel m, String className) {
        if (m.topic() == null || m.topic().isBlank()) {
            ctx.diagnostics()
                    .error(
                            m.method(),
                            "@KafkaSource on %s.%s() has a blank topic()".formatted(className, m.targetOperation()));
            return false;
        }
        return true;
    }

    /**
     * Validates that the method's {@code groupId()} is non-blank.
     *
     * @param m         the method model to check
     * @param className the simple name of the enclosing impl class (for the diagnostic message)
     * @return {@code true} when valid, {@code false} when a diagnostic was emitted
     */
    private boolean validateGroupId(KafkaSourceMethodModel m, String className) {
        if (m.groupId() == null || m.groupId().isBlank()) {
            ctx.diagnostics()
                    .error(
                            m.method(),
                            "@KafkaSource on %s.%s() has a blank groupId()".formatted(className, m.targetOperation()));
            return false;
        }
        return true;
    }
}
