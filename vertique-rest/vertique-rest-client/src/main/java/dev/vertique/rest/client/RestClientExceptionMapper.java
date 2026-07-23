// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client;

import dev.vertique.core.failure.FailureTranslator;

/**
 * Translates transport-level and HTTP-level exceptions thrown by a REST client proxy into typed
 * application exceptions.
 *
 * <p>Implementations hold a registry of {@link FailureTranslator}s keyed by exception type. When
 * {@link #translate(Throwable)} is called the mapper walks the exception class hierarchy to find
 * the most specific registered translator, applies it, and returns the result. If no translator
 * matches the original throwable is returned unchanged.
 *
 * <p>The fluent {@link #on(Class, FailureTranslator)} method makes it easy to build up a mapper
 * inline:
 *
 * <pre>{@code
 * RestClientExceptionMapper mapper = new DefaultRestClientExceptionMapper()
 *     .on(MyServiceException.class, t -> new DomainException("Service failed", t))
 *     .on(ConnectException.class, t -> new ServiceUnavailableException(t));
 * builder.exceptionMapper(mapper).build(MyClient.class);
 * }</pre>
 *
 * <p>The default implementation is {@link DefaultRestClientExceptionMapper}, which pre-registers
 * translators for common network errors ({@link java.net.ConnectException},
 * {@link java.net.UnknownHostException}, etc.) and timeouts.
 *
 * @see DefaultRestClientExceptionMapper
 * @see RestClientBuilder#exceptionMapper(RestClientExceptionMapper)
 * @see RestClientBuilder#onFailure(Class, FailureTranslator)
 */
public interface RestClientExceptionMapper {

    /**
     * Registers a translator for the given exception type. When the mapper encounters a throwable
     * of this type (or a subtype), it will invoke the translator to convert it. Returns {@code this}
     * for fluent chaining.
     *
     * @param <T> the exception type
     * @param type the exception class to register a translator for
     * @param translator the translator function that converts the exception
     * @return this mapper, for fluent chaining
     */
    <T extends Throwable> RestClientExceptionMapper on(Class<T> type, FailureTranslator<T> translator);

    /**
     * Translates the given throwable using the most specific registered translator. Returns the
     * original throwable unchanged if no translator is registered for its type or any of its
     * superclasses.
     *
     * @param exception the throwable to translate
     * @return the translated throwable, or the original if no translator matched
     */
    Throwable translate(Throwable exception);
}
