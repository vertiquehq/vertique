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
 * <p>The details reach clients verbatim, so an implementor builds every detail it creates without a
 * submitted value in a path, message, type or argument. A constraint message produced by an
 * application's own constraints passes through unchanged, so applications must not interpolate the
 * validated value into a message. The form of
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
