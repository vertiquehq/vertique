// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.runtime;

import dev.vertique.rest.jaxrs.ResourceMethodMeta;

/**
 * Metadata for a single field in a {@code @BeanParam} or {@code @RequestParams} composite
 * parameter object.
 *
 * <p>This is the public counterpart of the package-private
 * {@code ParameterExtractor.BeanFieldEntry} record. Generated
 * {@link GeneratedJaxRsBeanParamModel} companions reference this type directly instead of the
 * private one, allowing generated code in arbitrary consumer packages to compile without access
 * to {@code ParameterExtractor}'s internals.
 *
 * <p>The {@link ResourceMethodMeta.ParamMeta} component carries the full parameter descriptor
 * (name, source, type, component type, generic type, default value, and annotations) exactly as
 * it appears in a resource method's parameter list when the bean param is expanded.
 *
 * @param name the field name as declared in the bean class (from {@link java.lang.reflect.Field#getName()})
 * @param meta the parameter metadata for this field, including its JAX-RS source, type
 *             information, and any {@code @DefaultValue}
 */
public record BeanParamFieldMeta(String name, ResourceMethodMeta.ParamMeta meta) {}
