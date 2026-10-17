// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.validation;

import java.util.List;

/**
 * Implemented by validation exceptions whose structured violations a transport may render.
 *
 * <p>A transport that recognizes a carrier renders each {@link ViolationDetail} individually
 * instead of falling back to the exception message alone. The REST layer's default exception
 * mapping does this for every {@link dev.vertique.core.exception.ValidationException} that
 * implements this interface.
 *
 * <p>Implementors keep every detail value-free: the details reach clients verbatim, so a path,
 * message, type or argument must never contain a submitted value. The form of
 * {@link ViolationDetail#path()} is defined by the producing exception, for example dotted
 * property paths from {@link BeanValidationException} or JSON Pointers from a payload validator.
 */
public interface ViolationCarrier {

    /**
     * Returns the structured violations carried by this exception.
     *
     * @return an unmodifiable list of {@link ViolationDetail} records; never {@code null}
     */
    List<ViolationDetail> violations();
}
