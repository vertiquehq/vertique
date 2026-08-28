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
 * <p>The default factory is built lazily, on first use, by {@link DefaultValidatorHolder}:
 * when the application's Dagger graph binds its own {@link Validator}, {@link
 * #validate(Object, Optional)} never touches the holder class, so classloading never triggers the
 * default provider's bootstrap (and, on a classpath with no Bean Validation provider present, never
 * throws). Only a request that genuinely falls back to the default — no bound {@link Validator} — pays
 * the bootstrap cost, and only once, on the first such request.
 *
 * <p>This type performs no traversal, resolution, or request-time processing of its own beyond
 * delegating to the shared {@link Validator} — it exists solely to own that one shared instance and
 * expose it through a single bounded operation. Public because {@code prepare()} is generated into an
 * arbitrary application package that cannot reach a package-private framework type
 * ({@code dev.vertique.mcp.tool.McpToolInvoker}, the interface {@code prepare()} implements, is
 * itself public for the same reason); application code should not call this type directly.
 */
public final class McpBeanValidation {

    private McpBeanValidation() {}

    /**
     * Validates {@code value} through {@code validator} when present, falling back to the one shared
     * default {@link Validator} otherwise.
     *
     * <p>This is the seam a generated invoker's constructor-injected {@code Optional<Validator>} uses:
     * when the application's Dagger graph binds a {@link Validator} — for example one backed by a
     * Dagger-aware {@link jakarta.validation.ConstraintValidatorFactory} that can resolve an
     * {@code @Inject}-only {@link jakarta.validation.ConstraintValidator} — tool-input Bean Validation
     * runs through it, and the default factory in {@link DefaultValidatorHolder} is never initialized.
     * When the binding is absent, {@link DefaultValidatorHolder#VALIDATOR} is
     * resolved and validation runs through the default {@link Validator}.
     *
     * @param value the materialized tool-argument carrier to validate; must not be {@code null}
     * @param validator the optionally application-bound {@link Validator}; must not be {@code null}
     *     itself (use {@link Optional#empty()}, never a {@code null} reference)
     * @param <T> the carrier type
     * @return the set of constraint violations, empty when {@code value} satisfies every declared
     *     constraint
     */
    public static <T> Set<ConstraintViolation<T>> validate(T value, Optional<Validator> validator) {
        return validator.orElseGet(DefaultValidatorHolder::validator).validate(value);
    }

    /**
     * Lazily builds and holds the one process-wide default {@link Validator}, initialized only on
     * first access to {@link #validator()} — the classic initialization-on-demand holder idiom, which
     * relies on the JVM's class-initialization guarantees for thread safety without any explicit
     * locking: a bound application {@link Validator} must never
     * cause this class to be loaded or initialized.
     */
    private static final class DefaultValidatorHolder {
        private static final Validator VALIDATOR =
                Validation.buildDefaultValidatorFactory().getValidator();

        private DefaultValidatorHolder() {}

        private static Validator validator() {
            return VALIDATOR;
        }
    }
}
