// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import dev.vertique.rest.core.ProblemDetail;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;

/**
 * Hierarchy-aware {@link ExceptionMapper ExceptionMapper&lt;Throwable&gt;} with a fluent
 * registration API. Walks the exception class hierarchy to find the most specific
 * registered handler.
 *
 * <p>Used by the framework to provide default mappings (e.g., {@code WebApplicationException},
 * {@code IllegalArgumentException}, {@code Throwable}). Users can also create instances
 * with custom {@code .on()} registrations and contribute them via Dagger multibinding.
 *
 * <p>Falls back to a 500 Internal Server Error {@link ProblemDetail} response if
 * no handler matches.
 */
@Slf4j
public class DefaultExceptionMapper implements ExceptionMapper<Throwable> {

    private final Map<Class<? extends Throwable>, ExceptionMapper<?>> handlers = new ConcurrentHashMap<>();
    private final Map<Class<? extends Throwable>, ExceptionMapper<?>> cache = new ConcurrentHashMap<>();

    /**
     * Registers an exception mapper for the given exception type. Returns {@code this}
     * for fluent chaining. Clears the lookup cache so the new registration takes effect
     * immediately.
     *
     * @param <T>    the exception type handled by the mapper
     * @param type   the exception class to register a mapper for
     * @param mapper the mapper that converts exceptions of {@code type} to a {@link Response}
     * @return this instance for fluent chaining
     */
    public <T extends Throwable> DefaultExceptionMapper on(Class<T> type, ExceptionMapper<T> mapper) {
        handlers.put(type, mapper);
        cache.clear();
        return this;
    }

    /**
     * Converts the given throwable to a JAX-RS {@link Response} using the most specific
     * registered handler. Falls back to a 500 Internal Server Error {@link ProblemDetail}
     * response if no handler is found in the class hierarchy.
     *
     * @param throwable the exception to map
     * @return the HTTP response to send to the client
     */
    @Override
    @SuppressWarnings("unchecked")
    public Response toResponse(Throwable throwable) {
        ExceptionMapper<Throwable> mapper = (ExceptionMapper<Throwable>) findHandler(throwable.getClass());
        if (mapper != null) {
            return mapper.toResponse(throwable);
        }
        log.error("No exception mapper found for {}", throwable.getClass().getName(), throwable);
        return Response.status(500)
                .entity(ProblemDetail.of(500, "Internal Server Error"))
                .type("application/problem+json")
                .build();
    }

    /**
     * Returns {@code true} when {@link #toResponse} would dispatch to the handler registered for
     * {@link Throwable} itself — no more-specific {@link #on} registration matched.
     *
     * <p>Used by {@link ErrorPipeline} to decide whether the framework catch-all logging decision
     * applies: a typed default such as {@code IllegalArgumentException → 400} is not "unhandled".
     *
     * @param exceptionClass the exception class that would be mapped
     * @return {@code true} when only the {@code Throwable} catch-all would handle it
     */
    boolean handledByCatchAll(Class<? extends Throwable> exceptionClass) {
        ExceptionMapper<?> handler = findHandler(exceptionClass);
        ExceptionMapper<?> catchAll = handlers.get(Throwable.class);
        return handler != null && catchAll != null && handler == catchAll;
    }

    /**
     * Returns the cached handler for the given class, computing it via {@link #lookupHandler}
     * on first access.
     *
     * @param exceptionClass the exception class to find a handler for
     * @return the handler, or {@code null} if none found in the hierarchy
     */
    private ExceptionMapper<?> findHandler(Class<? extends Throwable> exceptionClass) {
        return cache.computeIfAbsent(exceptionClass, this::lookupHandler);
    }

    /**
     * Walks the superclass hierarchy of the given exception class to find the most specific
     * registered handler. Returns {@code null} if none is found.
     *
     * @param exceptionClass the exception class to look up
     * @return the most specific registered handler, or {@code null}
     */
    private ExceptionMapper<?> lookupHandler(Class<? extends Throwable> exceptionClass) {
        Class<?> current = exceptionClass;
        while (current != null && Throwable.class.isAssignableFrom(current)) {
            ExceptionMapper<?> mapper = handlers.get(current);
            if (mapper != null) {
                return mapper;
            }
            current = current.getSuperclass();
        }
        return null;
    }
}
