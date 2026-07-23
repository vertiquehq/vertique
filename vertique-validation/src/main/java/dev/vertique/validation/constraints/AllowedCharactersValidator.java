// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.validation.constraints;

import dev.vertique.core.sanitization.InputLocation;
import dev.vertique.core.sanitization.InputValueContext;
import dev.vertique.core.validation.CharacterPolicy;
import dev.vertique.core.validation.CharacterPolicyResult;
import jakarta.annotation.Nullable;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.ValidationException;

/**
 * Jakarta Bean Validation {@link ConstraintValidator} for the {@link AllowedCharacters} annotation.
 *
 * <p>Validates that a {@link CharSequence} value contains only characters permitted by the
 * {@link CharacterPolicy} specified in the annotation. The policy is resolved via a
 * {@link CharacterPolicyResolver} when one is injected (Dagger context), or via reflection
 * fallback (no-arg constructor) when not.
 *
 * <p>Null values are treated as valid. Pair with {@code @NotNull} or {@code @NotBlank} to
 * require a non-null value.
 *
 * <p>On failure, the default constraint violation is disabled and a detailed message is built
 * including the policy name and, when available, the failure reason from the policy result.
 *
 * @see AllowedCharacters
 * @see CharacterPolicy
 * @see CharacterPolicyResult
 * @see CharacterPolicyResolver
 */
public class AllowedCharactersValidator implements ConstraintValidator<AllowedCharacters, CharSequence> {

    private final @Nullable CharacterPolicyResolver resolver;
    private Class<? extends CharacterPolicy> policyClass;

    /** No-arg constructor for Hibernate Validator fallback (no Dagger context). */
    public AllowedCharactersValidator() {
        this.resolver = null;
    }

    /**
     * Constructor with Dagger-managed resolver for custom policy resolution.
     *
     * @param resolver the resolver to use for policy instantiation
     */
    public AllowedCharactersValidator(CharacterPolicyResolver resolver) {
        this.resolver = resolver;
    }

    /**
     * Captures the policy class from the constraint annotation.
     *
     * @param annotation the {@link AllowedCharacters} annotation instance
     */
    @Override
    public void initialize(AllowedCharacters annotation) {
        this.policyClass = annotation.policy();
    }

    /**
     * Validates that the value contains only characters allowed by the configured policy.
     *
     * <p>Returns {@code true} for {@code null} values (null-safe). On failure, disables the
     * default violation and adds a violation with a descriptive message including the policy
     * name and the failure reason.
     *
     * @param value   the value to validate; may be {@code null}
     * @param context the constraint validator context for building custom violations
     * @return {@code true} if the value is {@code null} or passes the policy; {@code false} otherwise
     */
    @Override
    public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        CharacterPolicy policy = resolvePolicy(policyClass);
        InputValueContext inputCtx = new InputValueContext(InputLocation.BODY, "", "", Object.class);
        CharacterPolicyResult result = policy.validate(value.toString(), inputCtx);
        if (!result.valid()) {
            context.disableDefaultConstraintViolation();
            String message = "contains characters not allowed by " + policyClass.getSimpleName()
                    + (result.reason() != null ? ": " + result.reason() : "");
            context.buildConstraintViolationWithTemplate(message).addConstraintViolation();
            return false;
        }
        return true;
    }

    /**
     * Resolves the policy for the given class.
     *
     * <p>When a {@link CharacterPolicyResolver} was injected at construction time, delegates to
     * it (enabling Dagger-managed policies with injected dependencies). Otherwise falls back to
     * reflection-based instantiation via the public no-arg constructor.
     *
     * @param policyClass the policy class to resolve
     * @return the resolved {@link CharacterPolicy} instance
     * @throws ValidationException if the class cannot be instantiated
     */
    private CharacterPolicy resolvePolicy(Class<? extends CharacterPolicy> policyClass) {
        if (resolver != null) {
            return resolver.resolve(policyClass);
        }
        try {
            return policyClass.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new ValidationException("Cannot instantiate CharacterPolicy: " + policyClass.getName(), e);
        }
    }
}
