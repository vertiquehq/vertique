// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.validation;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.ConstraintValidatorFactory;
import java.lang.annotation.Annotation;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DaggerConstraintValidatorFactoryTest {

    @Test
    void shouldResolveDaggerManagedValidator() {
        var managed = new TestValidator();
        var factory = new DaggerConstraintValidatorFactory(Set.of(managed), Set.of());

        var result = factory.getInstance(TestValidator.class);

        assertSame(managed, result);
    }

    @Test
    void shouldResolveThroughDelegateFactory() {
        var delegateValidator = new AnotherValidator();
        ConstraintValidatorFactory delegate = new ConstraintValidatorFactory() {
            @Override
            @SuppressWarnings("unchecked")
            public <T extends ConstraintValidator<?, ?>> T getInstance(Class<T> key) {
                if (key == AnotherValidator.class) return (T) delegateValidator;
                throw new RuntimeException("Unknown");
            }

            @Override
            public void releaseInstance(ConstraintValidator<?, ?> instance) {}
        };
        var factory = new DaggerConstraintValidatorFactory(Set.of(), Set.of(delegate));

        var result = factory.getInstance(AnotherValidator.class);

        assertSame(delegateValidator, result);
    }

    @Test
    void shouldFallBackToReflection() {
        var factory = new DaggerConstraintValidatorFactory(Set.of(), Set.of());

        var result = factory.getInstance(TestValidator.class);

        assertNotNull(result);
        assertInstanceOf(TestValidator.class, result);
    }

    @Test
    void shouldThrowForNonInstantiableValidator() {
        var factory = new DaggerConstraintValidatorFactory(Set.of(), Set.of());

        assertThrows(
                jakarta.validation.ValidationException.class,
                () -> factory.getInstance(NonInstantiableValidator.class));
    }

    // --- Test helpers ---

    public static class TestValidator implements ConstraintValidator<Annotation, Object> {
        @Override
        public boolean isValid(Object value, ConstraintValidatorContext context) {
            return true;
        }
    }

    public static class AnotherValidator implements ConstraintValidator<Annotation, Object> {
        @Override
        public boolean isValid(Object value, ConstraintValidatorContext context) {
            return true;
        }
    }

    public static class NonInstantiableValidator implements ConstraintValidator<Annotation, Object> {
        @SuppressWarnings("unused")
        private NonInstantiableValidator(String required) {}

        @Override
        public boolean isValid(Object value, ConstraintValidatorContext context) {
            return true;
        }
    }
}
