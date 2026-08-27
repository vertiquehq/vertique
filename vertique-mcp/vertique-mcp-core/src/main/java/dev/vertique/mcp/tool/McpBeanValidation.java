// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.tool;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Optional;
import java.util.Set;

/**
 * The one shared Jakarta Bean Validation {@link Validator} for stage 4 of the fixed request-time input
 * pipeline (contract §4.7).
 *
 * <p>{@link Validation#buildDefaultValidatorFactory()} builds a full XML/provider-discovery bootstrap,
 * which is expensive to repeat; every generated invoker's {@code prepare()} that runs Bean Validation
 * on a materialized tool-argument carrier shares this one instance instead of building its own. The
 * default validator factory and its {@link Validator} are documented thread-safe and reusable across
 * concurrent validations, so one process-wide instance is correct, not merely convenient.
 *
 * <p>This type performs no traversal, resolution, or request-time processing of its own beyond
 * delegating to the shared {@link Validator} — it exists solely to own that one shared instance and
 * expose it through a single bounded operation. Public because {@code prepare()} is generated into an
 * arbitrary application package that cannot reach a package-private framework type
 * ({@code dev.vertique.mcp.tool.McpToolInvoker}, the interface {@code prepare()} implements, is
 * itself public for the same reason); application code should not call this type directly.
 */
public final class McpBeanValidation {

    private static final Validator VALIDATOR =
            Validation.buildDefaultValidatorFactory().getValidator();

    private McpBeanValidation() {}

    /**
     * Validates {@code value} against its declared Jakarta Bean Validation constraints using the one
     * shared, thread-safe {@link Validator}.
     *
     * @param value the materialized tool-argument carrier to validate; must not be {@code null}
     * @param <T> the carrier type
     * @return the set of constraint violations, empty when {@code value} satisfies every declared
     *     constraint
     */
    public static <T> Set<ConstraintViolation<T>> validate(T value) {
        return VALIDATOR.validate(value);
    }

    /**
     * Validates {@code value} through {@code validator} when present, falling back to the one shared
     * default {@link Validator} otherwise (R38/W7).
     *
     * <p>This is the seam a generated invoker's constructor-injected {@code Optional<Validator>} uses:
     * when the application's Dagger graph binds a {@link Validator} — for example one backed by a
     * Dagger-aware {@link jakarta.validation.ConstraintValidatorFactory} that can resolve an
     * {@code @Inject}-only {@link jakarta.validation.ConstraintValidator} — tool-input Bean Validation
     * runs through it; when the binding is absent, behavior is byte-for-byte identical to {@link
     * #validate(Object)}.
     *
     * @param value the materialized tool-argument carrier to validate; must not be {@code null}
     * @param validator the optionally application-bound {@link Validator}; must not be {@code null}
     *     itself (use {@link Optional#empty()}, never a {@code null} reference)
     * @param <T> the carrier type
     * @return the set of constraint violations, empty when {@code value} satisfies every declared
     *     constraint
     */
    public static <T> Set<ConstraintViolation<T>> validate(T value, Optional<Validator> validator) {
        return validator.orElse(VALIDATOR).validate(value);
    }
}
