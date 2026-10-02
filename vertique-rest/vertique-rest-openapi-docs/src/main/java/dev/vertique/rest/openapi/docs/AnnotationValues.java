// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import dev.vertique.rest.core.RestConfigurationException;
import jakarta.annotation.Nullable;
import java.lang.annotation.Annotation;
import java.util.List;

/**
 * Reads Swagger annotations the way every part of the document does: finds the first annotation of
 * a type, treats a blank string member as its unset sentinel default, and refuses an annotation
 * reference the document cannot resolve.
 */
final class AnnotationValues {

    private AnnotationValues() {}

    /**
     * Tells whether an annotation string member is set.
     *
     * @param value the member value
     * @return {@code true} when it is neither {@code null} nor blank
     */
    static boolean isSet(@Nullable String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Returns an annotation string member when it is set.
     *
     * @param value the member value
     * @return the value when it is neither {@code null} nor blank, else {@code null}
     */
    @Nullable
    static String setOrNull(@Nullable String value) {
        return isSet(value) ? value : null;
    }

    /**
     * Returns the first annotation of a type.
     *
     * @param annotations the annotations, in order
     * @param type the annotation type
     * @param <A> the annotation type
     * @return the first annotation of the type, or {@code null} when there is none
     */
    @Nullable
    static <A extends Annotation> A first(List<Annotation> annotations, Class<A> type) {
        for (Annotation annotation : annotations) {
            if (type.isInstance(annotation)) {
                return type.cast(annotation);
            }
        }
        return null;
    }

    /**
     * Builds the failure of an annotation reference the document cannot resolve.
     *
     * @param subject the failure-message subject naming the application and its mount
     * @param operationId the runtime operation id
     * @param attribute the attribute and the input it is declared on, for example {@code
     *     @Parameter.ref on query parameter q}
     * @return the failure
     */
    static RestConfigurationException unresolvedReference(String subject, String operationId, String attribute) {
        return new RestConfigurationException(subject + ": operation '" + operationId + "' declares " + attribute
                + "; the document declares no reusable parameters, request bodies, or examples for it to name");
    }
}
