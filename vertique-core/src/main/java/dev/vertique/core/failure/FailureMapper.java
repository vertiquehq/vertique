// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.failure;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hierarchy-aware, context-aware exception translator registry. Given a throwable, walks the
 * superclass chain to find the most specific registered {@link FailureTranslator}, invokes it
 * (passing a context string to a {@link ContextAwareFailureTranslator}), and applies {@link
 * #fallback(Throwable, String)} when none matches. Lookups are cached for performance.
 *
 * <p>Usable directly for ad-hoc translation, and extended by the layer-specific mappers
 * ({@code RestExceptionMapper}, {@code ServiceExceptionMapper}, {@code DbExceptionMapper},
 * {@code DefaultRestClientExceptionMapper}) which pre-register translators and may override
 * {@link #fallback(Throwable, String)}.
 *
 * <p>Registering a translator for a class that is already registered is last-wins and clears the
 * lookup cache — higher-priority customizers can override framework defaults.
 */
public class FailureMapper {

    private final Map<Class<? extends Throwable>, FailureTranslator<?>> registry = new ConcurrentHashMap<>();
    private final Map<Class<? extends Throwable>, FailureTranslator<?>> cache = new ConcurrentHashMap<>();

    /** Creates an empty mapper. Translators are added via {@link #on(Class, FailureTranslator)}. */
    public FailureMapper() {}

    /**
     * Registers a translator for the given exception type. Clears the lookup cache.
     *
     * @param <T>        the exception type
     * @param type       the exception class to register a translator for
     * @param translator the translator to associate with the exception class
     * @return this mapper, for fluent chaining
     */
    public <T extends Throwable> FailureMapper on(Class<T> type, FailureTranslator<T> translator) {
        registry.put(type, translator);
        cache.clear();
        return this;
    }

    /**
     * Registers a context-aware translator for the given exception type. Clears the lookup cache.
     *
     * @param <T>        the exception type
     * @param type       the exception class to register a translator for
     * @param translator the context-aware translator to associate with the exception class
     * @return this mapper, for fluent chaining
     */
    public <T extends Throwable> FailureMapper on(Class<T> type, ContextAwareFailureTranslator<T> translator) {
        registry.put(type, translator);
        cache.clear();
        return this;
    }

    /**
     * Translates the throwable using the most specific registered translator, passing {@code
     * context} to a {@link ContextAwareFailureTranslator}. If no translator matches, returns
     * {@link #fallback(Throwable, String)}.
     *
     * @param throwable the exception to translate
     * @param context   contextual message describing the failed operation
     * @return the translated exception
     */
    @SuppressWarnings("unchecked")
    public Throwable translate(Throwable throwable, String context) {
        FailureTranslator<Throwable> translator = (FailureTranslator<Throwable>) findTranslator(throwable.getClass());
        if (translator == null) {
            return fallback(throwable, context);
        }
        if (translator instanceof ContextAwareFailureTranslator<?> contextAware) {
            return ((ContextAwareFailureTranslator<Throwable>) contextAware).translate(throwable, context);
        }
        return translator.translate(throwable);
    }

    /**
     * Translates the throwable using its own message as context.
     *
     * @param throwable the exception to translate
     * @return the translated exception
     */
    public Throwable translate(Throwable throwable) {
        return translate(throwable, throwable.getMessage());
    }

    /**
     * Returns the cached translator for the given exception class, computing it via a superclass
     * hierarchy walk on first access. Returns {@code null} if no translator is found anywhere in the
     * hierarchy up to (but not including) {@link Object}.
     *
     * @param exceptionClass the exception class to look up
     * @return the most specific registered translator, or {@code null} if none is found
     */
    public FailureTranslator<?> findTranslator(Class<? extends Throwable> exceptionClass) {
        return cache.computeIfAbsent(exceptionClass, this::lookupTranslator);
    }

    /**
     * The result when no registered translator matches. The default returns the original throwable
     * unchanged; subclasses override to wrap it (e.g. into a {@code DataAccessException}).
     *
     * @param throwable the untranslated exception
     * @param context   contextual message describing the failed operation
     * @return the fallback result (default: {@code throwable})
     */
    protected Throwable fallback(Throwable throwable, String context) {
        return throwable;
    }

    // --- Internal ---

    private FailureTranslator<?> lookupTranslator(Class<? extends Throwable> exceptionClass) {
        Class<?> current = exceptionClass;
        while (current != null && Throwable.class.isAssignableFrom(current)) {
            FailureTranslator<?> translator = registry.get(current);
            if (translator != null) {
                return translator;
            }
            current = current.getSuperclass();
        }
        return null;
    }
}
