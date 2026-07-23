// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.failure;

/**
 * Translates a specific exception type to another throwable.
 *
 * <p>Unlike JAX-RS {@code ExceptionMapper} (which produces an HTTP Response),
 * this translator operates at the domain level, translating between exception types
 * without any HTTP dependency.
 *
 * <p>Registered with layer mappers ({@code RestExceptionMapper},
 * {@code ServiceExceptionMapper}) via their {@code on(Class, FailureTranslator)} methods.
 *
 * <p>See {@link ContextAwareFailureTranslator} for the context-carrying variant.
 *
 * @param <T> the exception type this translator handles
 */
@FunctionalInterface
public interface FailureTranslator<T extends Throwable> {

    /**
     * Translates the given throwable to another exception.
     *
     * @param throwable the exception to translate
     * @return the translated exception
     */
    Throwable translate(T throwable);
}
