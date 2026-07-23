// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.config.source;

import dev.vertique.core.exception.ConfigurationException;

/**
 * Thrown by a {@link ConfigPropertySource} when an unrecoverable error occurs during property
 * lookup, or by a {@link ConfigPropertySourceFactory} when the source cannot be constructed
 * (e.g. schema validation failure, authentication failure).
 *
 * <p>An error <em>must never</em> silently degrade into a not-found result. When a source
 * encounters an I/O failure, authentication error, or any other condition it cannot recover from,
 * it MUST throw this exception rather than returning {@link java.util.Optional#empty()}.
 *
 * <h2>Message Contract</h2>
 * <p>Two message forms are supported:
 * <ul>
 *   <li><strong>Lookup failure</strong> (four-arg / three-arg constructors): message contains
 *       source name, key name, and non-secret detail. {@link #key()} returns the lookup key.</li>
 *   <li><strong>Create-time / schema failure</strong> (two-arg constructors): message is
 *       {@code "Property source '<sourceName>' failed: <detail>"}. {@link #key()} returns
 *       {@code null}. Use this form for schema validation errors and gateway construction
 *       failures that are not tied to a specific lookup key.</li>
 * </ul>
 * <p>Messages MUST NOT contain any resolved value.
 *
 * <h2>Hierarchy</h2>
 * <p>Extends {@link ConfigurationException} because a source failure during the bootstrap phase
 * is a startup/wiring failure — the application cannot be configured into a running state.
 *
 * @see ConfigPropertySource#lookup(String)
 * @see ConfigPropertySourceFactory
 */
public class ConfigPropertySourceException extends ConfigurationException {

    private final String sourceName;
    private final String key;

    /**
     * Constructs an exception for a lookup failure with a cause.
     *
     * @param sourceName the instance name of the failing source (from
     *                   {@link ConfigPropertySource#name()}); used for diagnostics only
     * @param key        the key that was being looked up; must not be a resolved value
     * @param detail     a non-secret description of what went wrong
     * @param cause      the underlying cause; may be {@code null}
     */
    public ConfigPropertySourceException(String sourceName, String key, String detail, Throwable cause) {
        super(buildLookupMessage(sourceName, key, detail), cause);
        this.sourceName = sourceName;
        this.key = key;
    }

    /**
     * Constructs an exception for a lookup failure without a cause.
     *
     * @param sourceName the instance name of the failing source (from
     *                   {@link ConfigPropertySource#name()}); used for diagnostics only
     * @param key        the key that was being looked up; must not be a resolved value
     * @param detail     a non-secret description of what went wrong
     */
    public ConfigPropertySourceException(String sourceName, String key, String detail) {
        this(sourceName, key, detail, null);
    }

    /**
     * Constructs an exception for a create-time or schema failure with a cause.
     *
     * <p>Use this form when the failure is not tied to a specific lookup key — for example,
     * schema validation errors, missing required configuration fields, or gateway authentication
     * failures. {@link #key()} returns {@code null} for exceptions constructed with this form.
     *
     * @param sourceName the instance name of the failing source; used for diagnostics only
     * @param detail     a non-secret description of what went wrong (e.g.
     *                   {@code "required field 'address' is missing or blank"})
     * @param cause      the underlying cause; may be {@code null}
     */
    public ConfigPropertySourceException(String sourceName, String detail, Throwable cause) {
        super(buildCreateMessage(sourceName, detail), cause);
        this.sourceName = sourceName;
        this.key = null;
    }

    /**
     * Constructs an exception for a create-time or schema failure without a cause.
     *
     * <p>Use this form when the failure is not tied to a specific lookup key. {@link #key()}
     * returns {@code null} for exceptions constructed with this form.
     *
     * @param sourceName the instance name of the failing source; used for diagnostics only
     * @param detail     a non-secret description of what went wrong
     */
    public ConfigPropertySourceException(String sourceName, String detail) {
        this(sourceName, detail, (Throwable) null);
    }

    /**
     * Returns the name of the source instance that failed.
     *
     * @return source name; never {@code null}
     */
    public String sourceName() {
        return sourceName;
    }

    /**
     * Returns the key that was being looked up when the failure occurred, or {@code null} for
     * create-time failures constructed with the two-arg form.
     *
     * @return the lookup key, or {@code null} for source-construction failures
     */
    public String key() {
        return key;
    }

    // --- Internals ---

    /**
     * Builds a lookup-failure message containing the source name, key, and detail.
     *
     * @param sourceName the source instance name
     * @param key        the lookup key
     * @param detail     the non-secret error detail
     * @return formatted message string
     */
    private static String buildLookupMessage(String sourceName, String key, String detail) {
        return "Property source '" + sourceName + "' failed to look up key '" + key + "': " + detail;
    }

    /**
     * Builds a create-time failure message containing the source name and detail.
     *
     * @param sourceName the source instance name
     * @param detail     the non-secret error detail
     * @return formatted message string
     */
    private static String buildCreateMessage(String sourceName, String detail) {
        return "Property source '" + sourceName + "' failed: " + detail;
    }
}
