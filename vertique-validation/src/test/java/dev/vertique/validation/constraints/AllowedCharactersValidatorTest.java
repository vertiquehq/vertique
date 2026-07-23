// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.validation.constraints;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.sanitization.InputValueContext;
import dev.vertique.core.validation.CharacterPolicy;
import dev.vertique.core.validation.CharacterPolicyBinding;
import dev.vertique.core.validation.CharacterPolicyResult;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorFactory;
import jakarta.validation.Validation;
import jakarta.validation.ValidationException;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link AllowedCharactersValidator} — null safety, single policy enforcement,
 * and repeatable annotation AND semantics.
 */
class AllowedCharactersValidatorTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    @Nested
    @DisplayName("null safety")
    class NullSafety {
        @Test
        @DisplayName("null value passes validation (delegates to @NotNull)")
        void shouldPassForNullValue() {
            var violations = validator.validate(new NullableHolder(null));
            assertTrue(violations.isEmpty());
        }
    }

    @Nested
    @DisplayName("single policy")
    class SinglePolicy {
        @Test
        @DisplayName("valid identifier passes @AllowedCharacters(IdentifierPolicy)")
        void shouldPassValidIdentifier() {
            var violations = validator.validate(new IdentifierHolder("valid_id-123"));
            assertTrue(violations.isEmpty());
        }

        @Test
        @DisplayName("string with space fails @AllowedCharacters(IdentifierPolicy)")
        void shouldFailStringWithSpace() {
            var violations = validator.validate(new IdentifierHolder("invalid id"));
            assertFalse(violations.isEmpty());
        }

        @Test
        @DisplayName("string with at-sign fails @AllowedCharacters(IdentifierPolicy)")
        void shouldFailStringWithAtSign() {
            var violations = validator.validate(new IdentifierHolder("bad@char"));
            assertFalse(violations.isEmpty());
        }
    }

    @Nested
    @DisplayName("repeatable annotations (AND semantics)")
    class RepeatableAnnotations {
        @Test
        @DisplayName("value passing both policies is accepted")
        void shouldPassWhenBothPoliciesAccept() {
            // "abc" passes both AllAlphaPolicy and AllLowercasePolicy
            var violations = validator.validate(new DualPolicyHolder("abc"));
            assertTrue(violations.isEmpty());
        }

        @Test
        @DisplayName("value failing first policy is rejected even if second would pass")
        void shouldFailWhenFirstPolicyRejects() {
            // "ABC" fails AllLowercasePolicy
            var violations = validator.validate(new DualPolicyHolder("ABC"));
            assertFalse(violations.isEmpty());
        }

        @Test
        @DisplayName("value failing second policy is rejected even if first would pass")
        void shouldFailWhenSecondPolicyRejects() {
            // "abc1" fails AllAlphaPolicy (digits not allowed) but passes AllLowercasePolicy
            var violations = validator.validate(new DualPolicyHolder("abc1"));
            assertFalse(violations.isEmpty());
        }
    }

    @Nested
    @DisplayName("resolver-injected validator")
    class ResolverInjected {

        @Test
        @DisplayName("policy with no public no-arg constructor resolves via injected resolver")
        void policyWithNoArgConstructorResolvesViaResolver() {
            // InjectablePolicy has no public no-arg constructor — plain HV would fail to instantiate it
            CharacterPolicyBinding binding =
                    new CharacterPolicyBinding(InjectablePolicy.class, new InjectablePolicy("ok"));
            CharacterPolicyResolver resolver = new CharacterPolicyResolver(Set.of(binding));

            // Build a factory using the resolver-aware constructors of the two validators
            ConstraintValidatorFactory cvFactory = new ConstraintValidatorFactory() {
                @Override
                @SuppressWarnings("unchecked")
                public <T extends ConstraintValidator<?, ?>> T getInstance(Class<T> key) {
                    if (key == AllowedCharactersValidator.class) {
                        return (T) new AllowedCharactersValidator(resolver);
                    }
                    if (key == AllowedCharactersObjectValidator.class) {
                        return (T) new AllowedCharactersObjectValidator(resolver);
                    }
                    try {
                        return key.getDeclaredConstructor().newInstance();
                    } catch (ReflectiveOperationException e) {
                        throw new ValidationException("Cannot instantiate: " + key.getName(), e);
                    }
                }

                @Override
                public void releaseInstance(ConstraintValidator<?, ?> instance) {}
            };

            try (ValidatorFactory factory = Validation.byDefaultProvider()
                    .configure()
                    .constraintValidatorFactory(cvFactory)
                    .buildValidatorFactory()) {
                Validator v = factory.getValidator();
                var violations = v.validate(new InjectableHolder("ok"));
                assertTrue(violations.isEmpty(), "expected valid — resolver supplies the policy instance");
            }
        }

        @Test
        @DisplayName("policy with no public no-arg constructor fails without resolver")
        void policyWithNoArgConstructorFailsWithoutResolver() {
            // Without the resolver the reflection fallback in the no-arg constructor path cannot
            // instantiate InjectablePolicy — a ValidationException is expected at validate time.
            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                Validator v = factory.getValidator();
                assertThrows(
                        ValidationException.class,
                        () -> v.validate(new InjectableHolder("ok")),
                        "expected ValidationException when policy has no public no-arg constructor");
            }
        }

        @Test
        @DisplayName("no-arg constructor path still works without resolver for stateless policy")
        void noArgConstructorFallbackWorksForStatelessPolicy() {
            // AllAlphaPolicy has a no-arg constructor — the default validator factory handles it
            var violations = validator.validate(new IdentifierHolder("validid"));
            assertTrue(violations.isEmpty(), "expected valid — stateless policy with no-arg constructor");
        }
    }

    // --- Test records ---

    record NullableHolder(
            @AllowedCharacters(policy = IdentifierPolicy.class)
            String value) {}

    record IdentifierHolder(
            @AllowedCharacters(policy = IdentifierPolicy.class)
            String value) {}

    record DualPolicyHolder(
            @AllowedCharacters(policy = AllAlphaPolicy.class) @AllowedCharacters(policy = AllLowercasePolicy.class)
            String value) {}

    record InjectableHolder(
            @AllowedCharacters(policy = InjectablePolicy.class)
            String value) {}

    // --- Test policies ---

    /** Allows only ASCII letters (a-z, A-Z). */
    public static class AllAlphaPolicy implements CharacterPolicy {
        @Override
        public CharacterPolicyResult validate(String value, InputValueContext context) {
            if (value == null) return CharacterPolicyResult.passed();
            int[] codePoints = value.codePoints().toArray();
            for (int i = 0; i < codePoints.length; i++) {
                int cp = codePoints[i];
                if (!Character.isLetter(cp) || cp > 0x7E) {
                    return CharacterPolicyResult.failed(i, cp, "only ASCII letters allowed");
                }
            }
            return CharacterPolicyResult.passed();
        }
    }

    /** Allows only lowercase ASCII letters (a-z). */
    public static class AllLowercasePolicy implements CharacterPolicy {
        @Override
        public CharacterPolicyResult validate(String value, InputValueContext context) {
            if (value == null) return CharacterPolicyResult.passed();
            int[] codePoints = value.codePoints().toArray();
            for (int i = 0; i < codePoints.length; i++) {
                int cp = codePoints[i];
                if (cp < 'a' || cp > 'z') {
                    return CharacterPolicyResult.failed(i, cp, "only lowercase ASCII letters allowed");
                }
            }
            return CharacterPolicyResult.passed();
        }
    }

    /**
     * A policy that requires Dagger injection — it has no public no-arg constructor.
     * The {@code allowedValue} simulates an injected dependency.
     */
    public static class InjectablePolicy implements CharacterPolicy {

        private final String allowedValue;

        /** Package-private constructor — not accessible via reflection from outside. */
        InjectablePolicy(String allowedValue) {
            this.allowedValue = allowedValue;
        }

        @Override
        public CharacterPolicyResult validate(String value, InputValueContext context) {
            if (value == null) return CharacterPolicyResult.passed();
            if (value.equals(allowedValue)) return CharacterPolicyResult.passed();
            return CharacterPolicyResult.failed(0, value.codePointAt(0), "value not in allowed set");
        }
    }
}
