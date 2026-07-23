// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client;

import dev.vertique.core.failure.FailureMapper;
import dev.vertique.core.failure.FailureTranslator;
import dev.vertique.rest.client.exception.RestClientConnectionException;
import dev.vertique.rest.client.exception.RestClientException;
import dev.vertique.rest.client.exception.RestClientTimeoutException;
import dev.vertique.rest.client.exception.RestClientUnavailableException;
import io.vertx.core.VertxException;
import io.vertx.core.http.HttpClosedException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeoutException;

/**
 * Default {@link RestClientExceptionMapper} that translates well-known JDK and Vert.x transport
 * exceptions into the framework's typed rest-client exceptions.
 *
 * <p><b>Note:</b> connection and timeout failures map to {@link RestClientConnectionException} /
 * {@link RestClientTimeoutException}, which extend {@link RestClientUnavailableException}
 * (&rarr; HTTP 503) and are <em>not</em> {@link RestClientException} subtypes. To catch them, use
 * the semantic root {@link RestClientUnavailableException} (or core {@code UnavailableException}),
 * not {@link RestClientException}.
 *
 * <p>The following translations are registered in the constructor:
 * <ul>
 *   <li>{@link ConnectException} → {@link RestClientConnectionException} (connection refused)</li>
 *   <li>{@link UnknownHostException} → {@link RestClientConnectionException} (DNS failure)</li>
 *   <li>{@link NoRouteToHostException} → {@link RestClientConnectionException} (routing
 *       failure)</li>
 *   <li>{@link SocketException} → {@link RestClientConnectionException} (OS-level socket
 *       error)</li>
 *   <li>{@link TimeoutException} → {@link RestClientTimeoutException} (covers Vert.x
 *       {@code NoStackTraceTimeoutException})</li>
 *   <li>{@link HttpClosedException} → {@link RestClientConnectionException} (empty/reset
 *       responses)</li>
 *   <li>{@link VertxException} → {@link RestClientException} (safety net for remaining Vert.x
 *       failures)</li>
 * </ul>
 *
 * <p>Additional translators can be registered at any time via {@link #on(Class, FailureTranslator)}.
 * When multiple translators are registered for a type hierarchy, the most specific match wins.
 *
 * <p>Delegation: translation is backed by {@link FailureMapper} which walks the superclass
 * hierarchy and caches lookups.
 *
 * @see RestClientExceptionMapper
 * @see RestClientBuilder#exceptionMapper(RestClientExceptionMapper)
 */
public class DefaultRestClientExceptionMapper implements RestClientExceptionMapper {

    private final FailureMapper failureMapper = new FailureMapper();

    /**
     * Creates a new mapper with the default network-error translations pre-registered.
     *
     * <p>Translations cover: connection refused, unknown host, no route to host, socket errors,
     * timeouts ({@link TimeoutException} and Vert.x subclasses), connection-close faults, and a
     * broad {@link VertxException} safety net.
     */
    public DefaultRestClientExceptionMapper() {
        on(ConnectException.class, t -> new RestClientConnectionException("Connection refused", t));
        on(UnknownHostException.class, t -> new RestClientConnectionException("Unknown host: " + t.getMessage(), t));
        on(
                NoRouteToHostException.class,
                t -> new RestClientConnectionException("No route to host: " + t.getMessage(), t));
        on(SocketException.class, t -> new RestClientConnectionException("Connection error: " + t.getMessage(), t));
        on(TimeoutException.class, t -> new RestClientTimeoutException("Request timed out: " + t.getMessage(), t));
        on(
                HttpClosedException.class,
                t -> new RestClientConnectionException("Connection closed: " + t.getMessage(), t));
        on(VertxException.class, t -> new RestClientException("Request failed: " + t.getMessage(), t));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Delegates to the underlying {@link FailureMapper#on(Class, FailureTranslator)}.
     * Clears the lookup cache so the new translator is picked up immediately.
     */
    @Override
    public <T extends Throwable> RestClientExceptionMapper on(Class<T> type, FailureTranslator<T> translator) {
        failureMapper.on(type, translator);
        return this;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Delegates to {@link FailureMapper#translate(Throwable)}, which walks the superclass
     * hierarchy of {@code exception} to find the most specific registered translator and returns the
     * original exception unchanged if none matches.
     */
    @Override
    public Throwable translate(Throwable exception) {
        return failureMapper.translate(exception);
    }
}
