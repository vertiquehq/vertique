// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.validation;

import dev.vertique.validation.constraints.AllowedCharacters;
import jakarta.validation.ConstraintViolation;
import java.lang.annotation.Annotation;
import java.util.Map;

/**
 * {@link ViolationArgsInspector} that extracts the policy class name from
 * {@link AllowedCharacters} constraint violations.
 *
 * <p>When an {@code @AllowedCharacters} constraint fails, this inspector contributes a
 * {@code "policy"} argument with the simple name of the {@link dev.vertique.core.validation.CharacterPolicy}
 * implementation class. This argument can be used by API error formatters to produce
 * descriptive validation error messages.
 *
 * <p>Example violation args: {@code {policy: "IdentifierPolicy"}}
 */
class AllowedCharactersArgsInspector implements ViolationArgsInspector {

    /**
     * Returns {@code true} for {@link AllowedCharacters} constraint annotations.
     *
     * @param constraintAnnotation the constraint annotation class to check
     * @return {@code true} if this inspector handles the annotation
     */
    @Override
    public boolean supports(Class<? extends Annotation> constraintAnnotation) {
        return AllowedCharacters.class.equals(constraintAnnotation);
    }

    /**
     * Extracts the policy class simple name from the {@link AllowedCharacters} annotation
     * on the given violation.
     *
     * @param violation the constraint violation to inspect
     * @return a map containing {@code {policy: "<PolicySimpleName>"}}, or {@code null} if
     *         the annotation is not an {@link AllowedCharacters} instance
     */
    @Override
    public Map<String, Object> extract(ConstraintViolation<?> violation) {
        Annotation annotation = violation.getConstraintDescriptor().getAnnotation();
        if (annotation instanceof AllowedCharacters ac) {
            return Map.of("policy", ac.policy().getSimpleName());
        }
        return null;
    }
}
