// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.convert;

import dev.vertique.core.exception.ValidationException;

/**
 * Thrown when a transport string cannot be parsed into the declared parameter type. Maps to HTTP 400
 * (input-validation semantics) via its {@link ValidationException} root.
 *
 * <p>The exception carries the parameter name, its {@link ParamSource}, and the target type so the
 * error pipeline can produce a precise field-level problem detail. The <em>raw value</em> is never
 * captured, to avoid leaking request data into error responses or logs.
 */
public class ParamConversionException extends ValidationException {

    private final String paramName;
    private final transient ParamSource source;
    private final transient Class<?> targetType;

    /**
     * Constructs a new conversion exception.
     *
     * @param message    the detail message (must not contain the raw value)
     * @param paramName  the declared parameter name
     * @param source     the transport source the parameter was read from
     * @param targetType the target type the value could not be parsed into
     */
    public ParamConversionException(String message, String paramName, ParamSource source, Class<?> targetType) {
        super(message);
        this.paramName = paramName;
        this.source = source;
        this.targetType = targetType;
    }

    /**
     * Constructs a new conversion exception with an underlying cause.
     *
     * @param message    the detail message (must not contain the raw value)
     * @param paramName  the declared parameter name
     * @param source     the transport source the parameter was read from
     * @param targetType the target type the value could not be parsed into
     * @param cause      the underlying parse failure
     */
    public ParamConversionException(
            String message, String paramName, ParamSource source, Class<?> targetType, Throwable cause) {
        super(message, cause);
        this.paramName = paramName;
        this.source = source;
        this.targetType = targetType;
    }

    /**
     * Returns the declared parameter name.
     *
     * @return the parameter name
     */
    public String paramName() {
        return paramName;
    }

    /**
     * Returns the transport source the parameter was read from.
     *
     * @return the parameter source
     */
    public ParamSource source() {
        return source;
    }

    /**
     * Returns the target type the value could not be parsed into.
     *
     * @return the target type
     */
    public Class<?> targetType() {
        return targetType;
    }
}
