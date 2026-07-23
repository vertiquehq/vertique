// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.validation.constraints;

import dev.vertique.core.validation.CharacterPolicy;
import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Validates that a string value contains only characters permitted by the specified
 * {@link CharacterPolicy}.
 *
 * <p>The policy class is resolved at validation time through a two-tier chain: first via
 * Dagger multibinding (if {@link CharacterPolicyResolver} is available), falling back to
 * reflection instantiation for stateless policies.
 *
 * <p>This annotation is repeatable — multiple {@code @AllowedCharacters} constraints on the
 * same element are evaluated independently (AND semantics: all must pass).
 *
 * <p>Null values are treated as valid; pair with {@code @NotNull} or {@code @NotBlank}
 * to require a non-null value.
 *
 * <p>Example usage:
 * <pre>{@code
 * @AllowedCharacters(policy = IdentifierPolicy.class)
 * private String name;
 *
 * @AllowedCharacters(policy = SlugPolicy.class)
 * private String slug;
 * }</pre>
 *
 * @see CharacterPolicy
 * @see AllowedCharactersValidator
 */
@Documented
@Target({
    ElementType.TYPE,
    ElementType.FIELD,
    ElementType.RECORD_COMPONENT,
    ElementType.PARAMETER,
    ElementType.TYPE_USE,
    ElementType.ANNOTATION_TYPE
})
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(AllowedCharacters.List.class)
@Constraint(validatedBy = {AllowedCharactersValidator.class, AllowedCharactersObjectValidator.class})
public @interface AllowedCharacters {

    /**
     * The character policy class to use for validation.
     *
     * @return the {@link CharacterPolicy} implementation class
     */
    Class<? extends CharacterPolicy> policy();

    /**
     * The message key for constraint violation messages.
     *
     * @return the message interpolation key
     */
    String message() default "{dev.vertique.validation.AllowedCharacters}";

    /**
     * The validation groups this constraint belongs to.
     *
     * @return the groups array
     */
    Class<?>[] groups() default {};

    /**
     * The payload for this constraint.
     *
     * @return the payload array
     */
    Class<? extends Payload>[] payload() default {};

    /**
     * Container annotation for repeatable {@link AllowedCharacters} constraints.
     *
     * <p>Multiple {@code @AllowedCharacters} annotations on the same element are wrapped
     * in this container and evaluated independently (AND semantics).
     */
    @Documented
    @Target({
        ElementType.TYPE,
        ElementType.FIELD,
        ElementType.RECORD_COMPONENT,
        ElementType.PARAMETER,
        ElementType.TYPE_USE,
        ElementType.ANNOTATION_TYPE
    })
    @Retention(RetentionPolicy.RUNTIME)
    @interface List {

        /**
         * The repeated {@link AllowedCharacters} constraints.
         *
         * @return the array of constraints
         */
        AllowedCharacters[] value();
    }
}
