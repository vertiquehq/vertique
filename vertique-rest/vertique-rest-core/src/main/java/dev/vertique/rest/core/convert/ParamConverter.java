// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.convert;

/**
 * Per-type, symmetric parameter-conversion SPI: converts a transport string to a typed value and
 * back. The same converter drives the JAX-RS inbound path ({@link #fromString(String)}) and the
 * REST-client outbound path ({@link #toString(Object)}).
 *
 * <p>Implementations must be stateless and thread-safe: a single converter instance is shared across
 * all requests for its target type.
 *
 * @param <T> the target type this converter produces and serializes
 */
public interface ParamConverter<T> {

    /**
     * Parses the given transport string into a typed value.
     *
     * <p>On a parse failure an implementation may throw <em>any</em> {@link RuntimeException} (e.g. a
     * {@link NumberFormatException}, a {@link java.time.format.DateTimeParseException}, or an
     * {@link IllegalArgumentException}). The framework's {@code ParamConversionResolver} catches that
     * raw failure and wraps it into a {@link ParamConversionException} carrying the parameter context
     * (name, source, target type), so implementations should <em>not</em> construct a
     * {@link ParamConversionException} themselves.
     *
     * @param value the raw string value from the request transport; never {@code null}
     * @return the parsed typed value
     * @throws RuntimeException if {@code value} cannot be parsed into {@code T}
     */
    T fromString(String value);

    /**
     * Serializes the given typed value into its transport string form.
     *
     * @param value the typed value to serialize; never {@code null}
     * @return the transport string representation of {@code value}
     */
    String toString(T value);
}
