// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.metadata;

import dev.vertique.rest.jaxrs.publication.InputKey;
import java.lang.annotation.Annotation;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The per-operation descriptor facts the document needs: the consumed media types, the part names of
 * named file parts, the resolved method and class annotations, and the element types of collection
 * parameters. They are taken on the calling thread before the publication is detached, so the
 * descriptor itself is never retained.
 *
 * <p>Internal to the OpenAPI documentation module; not an application API.
 *
 * @param consumes the consumed media types, in declaration order
 * @param namedFileParts the part names of the file parts that have a name, in declaration order
 * @param methodAnnotations the resolved annotations of the operation method, in declaration order
 * @param classAnnotations the resolved annotations of the declaring class, in declaration order
 * @param elementTypes the element type of each collection or multi-value parameter, keyed by the
 *     parameter's location and name; scalar parameters have no entry
 */
public record OperationFacts(
        List<String> consumes,
        List<String> namedFileParts,
        List<Annotation> methodAnnotations,
        List<Annotation> classAnnotations,
        Map<InputKey, Class<?>> elementTypes) {

    /** Stores unmodifiable copies of every collection; {@code null} collections and elements are rejected. */
    public OperationFacts {
        consumes = List.copyOf(consumes);
        namedFileParts = List.copyOf(namedFileParts);
        methodAnnotations = List.copyOf(methodAnnotations);
        classAnnotations = List.copyOf(classAnnotations);
        elementTypes = Collections.unmodifiableMap(new LinkedHashMap<>(elementTypes));
    }

    /**
     * Creates facts without annotations or element types.
     *
     * @param consumes the consumed media types, in declaration order
     * @param namedFileParts the part names of the file parts that have a name, in declaration order
     */
    public OperationFacts(List<String> consumes, List<String> namedFileParts) {
        this(consumes, namedFileParts, List.of(), List.of(), Map.of());
    }
}
