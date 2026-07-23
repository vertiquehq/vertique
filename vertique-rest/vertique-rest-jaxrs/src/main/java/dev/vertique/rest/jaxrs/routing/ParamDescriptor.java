// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.routing;

import jakarta.annotation.Nullable;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.List;

/**
 * Describes a single declared JAX-RS request parameter (path, query, header, cookie, or form) for
 * the validation/binding and schema-synthesis seams.
 *
 * <p>The {@link #location()} is expressed as a {@link ParamLocation} — the public, bindable
 * request-parameter locations — keeping the internal {@code ResourceMethodMeta} parameter model from
 * leaking through this SPI.
 *
 * @param name          the declared parameter name (from the JAX-RS annotation value or reflection)
 * @param location      where the parameter value comes from, expressed as a {@link ParamLocation}
 * @param type          the declared parameter type (raw class)
 * @param componentType the element type for collection/multi-value parameters (e.g. {@code String}
 *                      for {@code List<String>}); {@code null} for scalar parameters
 * @param genericType   the full generic type when relevant; {@code null} otherwise
 * @param defaultValue  the {@code @DefaultValue} string, or {@code null} when none is declared
 * @param annotations   the declared parameter annotations (e.g. {@code @Schema}, {@code @NotNull});
 *                      never {@code null}
 */
public record ParamDescriptor(
        String name,
        ParamLocation location,
        Class<?> type,
        @Nullable Class<?> componentType,
        @Nullable Type genericType,
        @Nullable String defaultValue,
        List<Annotation> annotations) {

    /**
     * Compact constructor defensively copying the annotation list to guarantee immutability.
     */
    public ParamDescriptor {
        annotations = annotations != null ? List.copyOf(annotations) : List.of();
    }
}
