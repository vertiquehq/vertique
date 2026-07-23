// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.convert;

import dev.vertique.core.exception.TechnicalException;

/**
 * Thrown at conversion time when no converter or provider can satisfy a declared parameter type.
 * Maps to HTTP 500 via its {@link TechnicalException} root: a request reaching this state is a
 * configuration gap that startup/build validation should have rejected, not a client error.
 *
 * <p>The exception carries the parameter name, its {@link ParamSource}, and the target type for
 * diagnostics. As with {@link ParamConversionException}, no raw value is captured.
 */
public class ParamConverterNotFoundException extends TechnicalException {

    private final String paramName;
    private final transient ParamSource source;
    private final transient Class<?> targetType;

    /**
     * Constructs a new not-found exception.
     *
     * @param message    the detail message
     * @param paramName  the declared parameter name
     * @param source     the transport source the parameter was read from
     * @param targetType the target type that has no registered converter or provider
     */
    public ParamConverterNotFoundException(String message, String paramName, ParamSource source, Class<?> targetType) {
        super(message);
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
     * Returns the target type that has no registered converter or provider.
     *
     * @return the target type
     */
    public Class<?> targetType() {
        return targetType;
    }
}
