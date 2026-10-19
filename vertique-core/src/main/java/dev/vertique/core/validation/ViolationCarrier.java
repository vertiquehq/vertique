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
 * {@link ViolationDetail#path()} is defined by the producing exception and reported by
 * {@link #violationPathForm()}, for example Java property paths from {@link BeanValidationException}
 * or JSON Pointers from a payload validator. A transport renders a path as a location only for a form
 * it knows to be a location, and a pointer is relative to the document the producer validated.
 */
public interface ViolationCarrier {

    /**
     * Returns the structured violations carried by this exception.
     *
     * @return an unmodifiable list of {@link ViolationDetail} records; never {@code null}
     */
    List<ViolationDetail> violations();

    /**
     * Returns the form of every detail path of this carrier.
     *
     * <p>The default is {@link ViolationPathForm#JAVA_PROPERTY_PATH}, so an existing implementation
     * keeps compiling and keeps its paths out of the location members of a rendered response. An
     * implementation whose paths are JSON Pointers overrides this method to return
     * {@link ViolationPathForm#JSON_POINTER} and must then hold every path to the guarantees stated
     * there. The pointer is relative to the producer's own document and mapper.
     *
     * @return the path form; never {@code null}
     */
    default ViolationPathForm violationPathForm() {
        return ViolationPathForm.JAVA_PROPERTY_PATH;
    }
}
