// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.failure;

/**
 * A {@link FailureTranslator} that also receives a context string (e.g. the operation being
 * attempted) describing the failure. Registered with a {@link FailureMapper} via {@link
 * FailureMapper#on(Class, ContextAwareFailureTranslator)} and invoked with the context supplied to
 * {@link FailureMapper#translate(Throwable, String)}.
 *
 * <p>Extends {@link FailureTranslator} so plain and context-aware translators share one registry;
 * the inherited no-context {@link #translate(Throwable)} defaults the context to the throwable's
 * message.
 *
 * @param <T> the exception type this translator handles
 */
@FunctionalInterface
public interface ContextAwareFailureTranslator<T extends Throwable> extends FailureTranslator<T> {

    /**
     * Translates the throwable to another exception, using {@code context} (e.g. an operation name)
     * to enrich the result.
     *
     * @param throwable the exception to translate
     * @param context   contextual message describing the failed operation
     * @return the translated exception
     */
    Throwable translate(T throwable, String context);

    /**
     * {@inheritDoc}
     *
     * <p>Defaults the context to {@link Throwable#getMessage()}.
     */
    @Override
    default Throwable translate(T throwable) {
        return translate(throwable, throwable.getMessage());
    }
}
