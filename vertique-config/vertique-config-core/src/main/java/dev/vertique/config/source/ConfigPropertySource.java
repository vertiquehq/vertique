// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.config.source;

import java.util.Optional;

/**
 * A named property source that resolves placeholder keys to string values during bootstrap.
 *
 * <p>Implementations are created by {@link ConfigPropertySourceFactory#create(String,
 * io.vertx.core.json.JsonObject)} and consulted in declaration order by the bootstrap engine.
 * Sources are called only during single-threaded boot; results are memoized per
 * {@code (source, key)} by the engine so {@link #lookup(String)} is called at most once per key.
 * Resolved values are treated as literal — they are never re-expanded for nested placeholders.
 *
 * <h2>Not-Found vs Error Contract</h2>
 * <ul>
 *   <li>Return {@link Optional#empty()} to signal that the key was not found. The resolution
 *       chain continues to the next declared source.</li>
 *   <li>Throw {@link ConfigPropertySourceException} to signal an unrecoverable error. Startup
 *       is aborted immediately. An error <em>must never</em> silently degrade into a not-found
 *       result.</li>
 * </ul>
 *
 * <h2>Value Redaction</h2>
 * <p>Implementations MUST NOT include resolved values in log messages, exception messages,
 * or any other observable output. Exception messages MUST contain only the source name, the key,
 * and a non-secret detail string.
 *
 * @see ConfigPropertySourceFactory
 * @see ConfigPropertySourceException
 */
public interface ConfigPropertySource extends AutoCloseable {

    /**
     * Returns the instance name of this source, as declared in the application configuration.
     *
     * <p>Used only for diagnostic output — never for business logic.
     *
     * @return the source name; never {@code null}
     */
    String name();

    /**
     * Looks up the given key in this source.
     *
     * <p>{@link Optional#empty()} means the key was not found in this source; the resolution
     * chain continues to the next source. A thrown {@link ConfigPropertySourceException} means
     * an unrecoverable error occurred and startup must be aborted.
     *
     * <p>Called only during single-threaded boot. Results are memoized per
     * {@code (source, key)} by the engine so this method is invoked at most once per key.
     *
     * @param key the placeholder key to resolve; never {@code null}
     * @return an {@link Optional} containing the resolved value, or {@link Optional#empty()} if
     *         the key is not present in this source
     * @throws ConfigPropertySourceException if an unrecoverable error prevents the lookup;
     *                                        message MUST contain source name + key + detail,
     *                                        MUST NOT contain any resolved value
     */
    Optional<String> lookup(String key);

    /**
     * Releases any resources held by this source.
     *
     * <p>Invoked exactly once at shutdown, in reverse declaration order. Implementations
     * MUST NOT throw — any exception is a programming error. The default implementation is
     * a no-op, suitable for stateless or connection-free sources.
     *
     * <p>Exceptions thrown by this callback are caught, logged, and swallowed; they do not affect
     * the enclosing operation.
     */
    @Override
    default void close() {}
}
