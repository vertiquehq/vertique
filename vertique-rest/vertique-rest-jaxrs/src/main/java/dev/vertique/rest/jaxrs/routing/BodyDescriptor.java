// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.routing;

import jakarta.annotation.Nullable;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.List;

/**
 * Describes the request body of a JAX-RS operation, for request binding and schema synthesis.
 *
 * @param type        the declared body type (raw class)
 * @param genericType the full generic type (e.g. {@code List<MyPojo>}); {@code null} when the body
 *                    is non-generic
 * @param annotations the declared body annotations (e.g. {@code @Schema}, {@code @Valid}); never
 *                    {@code null}
 */
public record BodyDescriptor(Class<?> type, @Nullable Type genericType, List<Annotation> annotations) {

    /**
     * Compact constructor defensively copying the annotation list to guarantee immutability.
     */
    public BodyDescriptor {
        annotations = annotations != null ? List.copyOf(annotations) : List.of();
    }
}
