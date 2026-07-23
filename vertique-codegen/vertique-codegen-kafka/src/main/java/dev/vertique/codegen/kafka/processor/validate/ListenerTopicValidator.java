// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.kafka.processor.validate;

import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.Diagnostics;
import dev.vertique.codegen.kafka.processor.scan.ListenerModel;

/**
 * Validates that a {@link dev.vertique.kafka.KafkaListener @KafkaListener}'s {@code topic()}
 * attribute is not blank (FR-CG006-005).
 *
 * <p>An empty or whitespace-only topic prevents the consumer from being bound to any Kafka topic
 * at runtime. This check applies to both Model 3 router and Model 4 direct-handler listeners.
 */
public final class ListenerTopicValidator {

    private final CodegenContext ctx;

    /**
     * Constructs the validator bound to the given codegen context.
     *
     * @param ctx the shared codegen context; must not be {@code null}
     */
    public ListenerTopicValidator(CodegenContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Returns {@code true} when the listener's {@code topic()} is non-blank; emits a compiler error
     * and returns {@code false} otherwise.
     *
     * @param model the scanned listener model to validate; must not be {@code null}
     * @return {@code true} when valid, {@code false} when a diagnostic was emitted
     */
    public boolean validate(ListenerModel model) {
        if (model.topic() == null || model.topic().isBlank()) {
            ctx.diagnostics()
                    .error(
                            model.originType(),
                            Diagnostics.kafkaListenerBlankTopic(
                                    model.originType().getSimpleName().toString()));
            return false;
        }
        return true;
    }
}
