// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.rest.client.processor.scan;

import dev.vertique.codegen.rest.client.processor.ParamModel;
import java.util.List;
import javax.lang.model.element.TypeElement;

/**
 * APT-side model for a {@code @BeanParam} bean type, produced by {@link BeanParamScanner}.
 *
 * <p>The {@code fullyGeneratable} flag indicates whether a zero-reflection
 * {@code {Bean}_BeanParamAccessor} can be emitted for this bean type. When {@code false}, at
 * least one field is not accessible via a public record component accessor, public getter, or
 * same-package field — the processor emits a note and leaves the bean to the reflective fallback.
 *
 * @param beanType the type element of the bean (e.g. the {@code PageRequest} class)
 * @param fields the ordered list of parameter models for all annotated fields or record components
 * @param fullyGeneratable {@code true} when all fields can be accessed without {@code setAccessible}
 */
public record BeanModel(TypeElement beanType, List<ParamModel> fields, boolean fullyGeneratable) {}
