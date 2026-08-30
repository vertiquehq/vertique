// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.tool;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import jakarta.validation.executable.ExecutableValidator;
import jakarta.validation.metadata.BeanDescriptor;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Provider-independence proof for repair task R47 (phase-exit review): a bound application {@link
 * Validator} must never trigger {@code McpBeanValidation}'s default-provider bootstrap.
 *
 * <p><strong>Form chosen.</strong> {@code McpBeanValidation}'s default factory now lives behind a
 * private, lazily initialized holder class ({@code DefaultValidatorHolder}) with no public accessor
 * and no observable side effect this test tree can reach without JVM-internal hacks (there is no
 * standard reflective API to query "has this class been initialized" without itself triggering
 * initialization). This test therefore pins the mechanism that guarantees the holder is never
 * touched — {@link Optional#orElseGet(java.util.function.Supplier)}'s short-circuit semantics — by
 * passing a bound, fully instrumented fake {@link Validator} and asserting it alone was consulted:
 * its own {@code validate} method was invoked exactly once, and the exact {@link Set} instance it
 * returns is what {@code validate(Object, Optional)} hands back, unmodified and unmerged with any
 * other validator's output. Since {@code validate(T, Optional<Validator>)} is implemented as {@code
 * validator.orElseGet(DefaultValidatorHolder::validator)}, a present {@code validator} means the
 * {@code DefaultValidatorHolder::validator} supplier is never invoked at all — {@code orElseGet}
 * only calls its supplier when the {@link Optional} is empty — so the default holder class is never
 * even referenced, let alone initialized, on this path.
 */
class McpBeanValidationProviderIndependenceTest {

    @Test
    @DisplayName("validate(value, Optional.of(validator)) consults only the bound validator, "
            + "never the default-provider holder")
    void boundValidatorAloneIsConsulted() {
        Object value = new Object();
        AtomicInteger invocationCount = new AtomicInteger();
        Set<ConstraintViolation<Object>> sentinel = Set.of();
        RecordingValidator recordingValidator = new RecordingValidator(invocationCount, sentinel);

        Set<ConstraintViolation<Object>> result = McpBeanValidation.validate(value, Optional.of(recordingValidator));

        assertThat(invocationCount.get())
                .as("the bound validator's validate(Object) must be invoked exactly once")
                .isOne();
        assertThat(result)
                .as("the exact Set instance the bound validator returned must be handed back "
                        + "unmodified, proving no other (default) validator's output was merged or "
                        + "substituted in")
                .isSameAs(sentinel);
        assertThat(recordingValidator.observedValue)
                .as("the bound validator must have received the exact value under validation")
                .isSameAs(value);
    }

    /**
     * A {@link Validator} that records its single supported invocation and refuses every other
     * method call, so any accidental fallback to a real (default) validator implementation — which
     * would need methods beyond {@code validate} — surfaces immediately as a test failure instead of
     * silently succeeding.
     */
    private static final class RecordingValidator implements Validator {
        private final AtomicInteger invocationCount;
        private final Set<ConstraintViolation<Object>> result;
        private volatile Object observedValue;

        private RecordingValidator(AtomicInteger invocationCount, Set<ConstraintViolation<Object>> result) {
            this.invocationCount = invocationCount;
            this.result = result;
        }

        @Override
        public <T> Set<ConstraintViolation<T>> validate(T object, Class<?>... groups) {
            invocationCount.incrementAndGet();
            observedValue = object;
            @SuppressWarnings("unchecked")
            Set<ConstraintViolation<T>> typed = (Set<ConstraintViolation<T>>) (Set<?>) result;
            return typed;
        }

        @Override
        public <T> Set<ConstraintViolation<T>> validateProperty(T object, String propertyName, Class<?>... groups) {
            throw new UnsupportedOperationException("not consulted by McpBeanValidation.validate");
        }

        @Override
        public <T> Set<ConstraintViolation<T>> validateValue(
                Class<T> beanType, String propertyName, Object value, Class<?>... groups) {
            throw new UnsupportedOperationException("not consulted by McpBeanValidation.validate");
        }

        @Override
        public BeanDescriptor getConstraintsForClass(Class<?> clazz) {
            throw new UnsupportedOperationException("not consulted by McpBeanValidation.validate");
        }

        @Override
        public <T> T unwrap(Class<T> type) {
            throw new UnsupportedOperationException("not consulted by McpBeanValidation.validate");
        }

        @Override
        public ExecutableValidator forExecutables() {
            throw new UnsupportedOperationException("not consulted by McpBeanValidation.validate");
        }
    }
}
