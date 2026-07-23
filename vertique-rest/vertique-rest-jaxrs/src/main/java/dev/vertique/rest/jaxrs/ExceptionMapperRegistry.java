// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import dev.vertique.rest.core.ProblemDetail;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;

/**
 * Hierarchy-aware registry of JAX-RS {@link ExceptionMapper ExceptionMapper} instances.
 * Walks the exception class hierarchy to find the most specific registered mapper.
 * Caches lookups for performance.
 *
 * <p>The registry is initialized with a {@link DefaultExceptionMapper} that handles framework
 * defaults (e.g., {@code WebApplicationException}, {@code IllegalArgumentException},
 * {@code Throwable}), registered for the {@code Throwable} type. User-contributed mappers
 * from the Dagger {@code Set<ExceptionMapper<?>>} multibinding are then registered, each
 * resolved to its specific exception type via {@link ExceptionMapperResolver}. User mappers overwrite
 * framework defaults for the same exception type (user wins).
 *
 * <p>If no mapper is found, returns a 500 Internal Server Error with
 * {@link ProblemDetail} body as the default fallback.
 */
@Slf4j
public class ExceptionMapperRegistry {

    private final Map<Class<? extends Throwable>, ExceptionMapper<?>> registry;
    private final Map<Class<? extends Throwable>, ExceptionMapper<?>> cache = new ConcurrentHashMap<>();
    private final Map<Class<? extends Throwable>, Boolean> specificMapperCache = new ConcurrentHashMap<>();

    /**
     * Creates a registry from the framework's {@link DefaultExceptionMapper} and user-contributed
     * mappers from the Dagger {@code Set<ExceptionMapper<?>>} multibinding.
     *
     * <p>The {@code defaults} mapper is registered for the {@code Throwable} type, providing
     * hierarchy-aware fallback for all exceptions. User mappers are then resolved and registered
     * by their specific exception type, overwriting any framework default for the same type.
     *
     * @param defaults the framework default exception mapper, pre-configured with built-in mappings
     * @param mappers  the set of user-contributed {@link ExceptionMapper} instances to register
     */
    public ExceptionMapperRegistry(DefaultExceptionMapper defaults, Set<ExceptionMapper<?>> mappers) {
        this.registry = new ConcurrentHashMap<>();

        // Framework defaults: register the DefaultExceptionMapper for Throwable
        registry.put(Throwable.class, defaults);

        // User mappers: resolve T from each and register (overwrites framework defaults for same type)
        registry.putAll(ExceptionMapperResolver.resolve(mappers));
    }

    /**
     * Registers an exception mapper at runtime. Clears the lookup cache.
     *
     * @param <T>           the exception type
     * @param exceptionType the exception class to register a mapper for
     * @param mapper        the mapper that converts the exception to a {@link Response}
     */
    public <T extends Throwable> void register(Class<T> exceptionType, ExceptionMapper<T> mapper) {
        registry.put(exceptionType, mapper);
        cache.clear();
        specificMapperCache.clear();
    }

    /**
     * Converts a throwable to a JAX-RS {@link Response} using the most specific registered mapper.
     * Falls back to a 500 Internal Server Error {@link ProblemDetail} response if no mapper is found.
     *
     * @param throwable the exception to map
     * @return the HTTP response to send to the client
     */
    @SuppressWarnings("unchecked")
    public Response toResponse(Throwable throwable) {
        ExceptionMapper<Throwable> mapper = (ExceptionMapper<Throwable>) findMapper(throwable.getClass());
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
     * Returns the cached mapper for the given class, computing it via {@link #lookupMapper} on first access.
     *
     * @param exceptionClass the exception class to find a mapper for
     * @return the mapper, or {@code null} if none found in the hierarchy
     */
    private ExceptionMapper<?> findMapper(Class<? extends Throwable> exceptionClass) {
        return cache.computeIfAbsent(exceptionClass, this::lookupMapper);
    }

    /**
     * Returns {@code true} if a mapper is registered for the given exception class or any of its
     * superclasses <em>before</em> reaching {@link Throwable}. This indicates that a specific mapper
     * (typically user-contributed via Dagger multibinding) handles this exception type, as opposed
     * to the {@link DefaultExceptionMapper} catch-all registered at the {@code Throwable} level.
     *
     * <p>Used by the error pipeline to decide whether the Vert.x status code fallback should apply:
     * if a specific mapper matched, the mapper's response wins; if only the catch-all matched, the
     * Vert.x-intended status code takes precedence.
     *
     * @param exceptionClass the exception class to check
     * @return {@code true} if a specific (non-catch-all) mapper exists in the hierarchy
     */
    public boolean hasSpecificMapper(Class<? extends Throwable> exceptionClass) {
        return specificMapperCache.computeIfAbsent(exceptionClass, this::lookupSpecificMapper);
    }

    /**
     * Walks the superclass hierarchy to check for a registered mapper before reaching
     * {@link Throwable}. Used by {@link #hasSpecificMapper} with caching.
     *
     * @param exceptionClass the exception class to check
     * @return {@code true} if a specific mapper exists
     */
    private boolean lookupSpecificMapper(Class<? extends Throwable> exceptionClass) {
        Class<?> current = exceptionClass;
        while (current != null && current != Throwable.class && Throwable.class.isAssignableFrom(current)) {
            if (registry.containsKey(current)) {
                return true;
            }
            current = current.getSuperclass();
        }
        return false;
    }

    /**
     * Walks the superclass hierarchy of the given exception class to find the most specific
     * registered mapper. Returns {@code null} if none is found.
     *
     * @param exceptionClass the exception class to look up
     * @return the most specific registered mapper, or {@code null}
     */
    private ExceptionMapper<?> lookupMapper(Class<? extends Throwable> exceptionClass) {
        Class<?> current = exceptionClass;
        while (current != null && Throwable.class.isAssignableFrom(current)) {
            ExceptionMapper<?> mapper = registry.get(current);
            if (mapper != null) {
                return mapper;
            }
            current = current.getSuperclass();
        }
        return null;
    }
}
