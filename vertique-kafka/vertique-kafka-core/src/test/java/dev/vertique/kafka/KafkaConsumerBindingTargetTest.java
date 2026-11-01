// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.Future;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the rule {@link KafkaConsumerBinding.Builder#build()} applies to a binding's
 * delivery target: a binding names a record handler, or a {@code dispatchTo} target service, but
 * not both. A binding that names neither still builds, because refusing it is the scanner's job.
 */
class KafkaConsumerBindingTargetTest {

    private static final String BINDING_NAME = "target-rule-binding";

    private static final KafkaRecordHandler<String> HANDLER = message -> Future.succeededFuture();

    // --- Tests ---

    @Test
    @DisplayName("a binding naming both a handler and a target is refused, and one naming neither builds as today")
    void bindingNamingBothHandlerAndTargetIsRefusedAndNeitherBuildsAsToday() {
        // Given: four builders for the same name, topic and group
        UnaryOperator<KafkaConsumerBinding.Builder<String>> both =
                builder -> builder.handler(HANDLER).dispatchTo(String.class, "process");
        UnaryOperator<KafkaConsumerBinding.Builder<String>> neither = builder -> builder;
        UnaryOperator<KafkaConsumerBinding.Builder<String>> handlerOnly = builder -> builder.handler(HANDLER);
        UnaryOperator<KafkaConsumerBinding.Builder<String>> targetOnly =
                builder -> builder.dispatchTo(String.class, "process");

        // When / Then: both is refused, and the refusal names the binding
        RuntimeException refusal = assertThrows(RuntimeException.class, () -> binding(both));
        assertTrue(
                String.valueOf(refusal.getMessage()).contains(BINDING_NAME),
                "the refusal should name the binding, was: " + refusal.getMessage());

        // When / Then: neither builds with no handler and no target
        KafkaConsumerBinding<String> bare = binding(neither);
        assertNull(bare.handler(), "a binding naming neither has no handler");
        assertNull(bare.targetService(), "a binding naming neither has no target service");

        // When / Then: handler only builds with the handler and no target
        KafkaConsumerBinding<String> handling = binding(handlerOnly);
        assertSame(HANDLER, handling.handler(), "a handler binding reports its handler");
        assertNull(handling.targetService(), "a handler binding has no target service");

        // When / Then: dispatchTo only builds with the target and no handler
        KafkaConsumerBinding<String> dispatching = binding(targetOnly);
        assertSame(String.class, dispatching.targetService(), "a dispatchTo binding reports its target service");
        assertNull(dispatching.handler(), "a dispatchTo binding has no handler");
    }

    @Test
    @DisplayName("a binding that sets a handler-only option without a handler is refused, naming the binding")
    void bindingSettingAHandlerOnlyOptionWithoutAHandlerIsRefused() {
        // Given: builders that set each handler-only option, with a dispatchTo target and with nothing
        List<UnaryOperator<KafkaConsumerBinding.Builder<String>>> options = List.of(
                builder -> builder.ignoreUndecodable(() -> {}),
                KafkaConsumerBinding.Builder::noHeaderContext,
                KafkaConsumerBinding.Builder::forceStrategies);

        for (UnaryOperator<KafkaConsumerBinding.Builder<String>> option : options) {
            // When / Then: the option beside a dispatchTo target, or alone, is refused and the refusal names the
            // binding
            for (UnaryOperator<KafkaConsumerBinding.Builder<String>> target :
                    List.<UnaryOperator<KafkaConsumerBinding.Builder<String>>>of(
                            builder -> builder.dispatchTo(String.class, "process"), builder -> builder)) {
                IllegalStateException refusal = assertThrows(
                        IllegalStateException.class, () -> binding(builder -> option.apply(target.apply(builder))));
                assertTrue(
                        String.valueOf(refusal.getMessage()).contains(BINDING_NAME),
                        "the refusal should name the binding, was: " + refusal.getMessage());
            }

            // When / Then: the same option beside a handler builds
            KafkaConsumerBinding<String> handling = binding(builder -> option.apply(builder.handler(HANDLER)));
            assertSame(HANDLER, handling.handler(), "a handler binding keeps its handler with the option");
        }
    }

    // --- Helpers ---

    /**
     * Builds a binding with a fixed name, topic and group, after the given step has set its
     * delivery target.
     *
     * @param builderStep sets the handler and/or the {@code dispatchTo} target on the builder
     * @return the built binding
     */
    private static KafkaConsumerBinding<String> binding(
            UnaryOperator<KafkaConsumerBinding.Builder<String>> builderStep) {
        KafkaConsumerBinding.Builder<String> builder = KafkaConsumerBinding.builder(BINDING_NAME, String.class)
                .topic("target.rule.topic")
                .groupId("target-rule-group");
        return builderStep.apply(builder).build();
    }
}
