// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.localization.message;

/**
 * Thrown by {@link MessageSource} implementations when a message code cannot be resolved in any
 * configured bundle and no default message has been supplied (FR-LOC-026).
 *
 * <p>The accessors mirror the convention of {@link java.util.MissingResourceException}:
 * <ul>
 *   <li>{@link #bundleBaseName()} — analogous to {@code MissingResourceException.getClassName()}</li>
 *   <li>{@link #code()} — analogous to {@code MissingResourceException.getKey()}</li>
 * </ul>
 *
 * <p>This exception extends {@link MessageSourceException} which in turn extends
 * {@link dev.vertique.localization.LocalizationException}. It does <em>not</em> directly extend
 * {@link dev.vertique.core.exception.TechnicalException}; HTTP-status mapping is applied by
 * the REST layer (see PRD §"Framework exception alignment").
 *
 * @see MessageSourceException
 * @see MessageSource
 */
public class NoSuchMessageException extends MessageSourceException {

    /** The resource bundle base name in which the code was sought. */
    private final String bundleBaseName;

    /** The message code that could not be resolved. */
    private final String code;

    /**
     * Constructs a new exception with the bundle base name, code, and a descriptive message.
     *
     * @param bundleBaseName the resource bundle base name in which the code was looked up
     * @param code           the message code that was not found
     * @param message        the detail message (typically includes both fields for readability)
     */
    public NoSuchMessageException(String bundleBaseName, String code, String message) {
        super(message);
        this.bundleBaseName = bundleBaseName;
        this.code = code;
    }

    /**
     * Returns the resource bundle base name in which the message code was sought.
     *
     * @return the bundle base name; may be {@code null} if the source is anonymous
     */
    public String bundleBaseName() {
        return bundleBaseName;
    }

    /**
     * Returns the message code that could not be resolved.
     *
     * @return the missing message code; never {@code null}
     */
    public String code() {
        return code;
    }
}
