// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client;

import java.util.List;

/**
 * Strategy for extracting field values from a {@code @BeanParam} bean instance.
 *
 * <p>This interface is the SPI used by {@link BeanParamAccessorRegistry} to resolve how to
 * extract individual field values from a bean type. Two implementations are provided:
 * <ul>
 *   <li>{@link ReflectiveBeanParamAccessor} — the default fallback, identical behaviour to the
 *       prior {@code RestClientRequestFactory.extractFieldValue} reflection path.</li>
 *   <li>Generated {@code {BeanType}_BeanParamAccessor} — zero-reflection, emitted by the
 *       {@code vertique-codegen-rest-client} annotation processor (Pass B).</li>
 * </ul>
 *
 * <p>Implementations are expected to return {@code null} when a field cannot be resolved,
 * matching the semantics of the prior reflective path.
 *
 * <p>Generated implementations MUST expose a public no-arg constructor so that
 * {@link BeanParamAccessorRegistry} can instantiate them via reflection-by-name.
 *
 * @param <T> the bean type this accessor operates on
 */
public interface BeanParamAccessor<T> {

    /**
     * Extracts the value of the named field from the given bean instance.
     *
     * <p>The {@code fieldName} matches the Java field or record-component name
     * (the accessor name stored in {@link dev.vertique.rest.client.meta.ClientParamMeta#accessorName()}).
     *
     * @param bean the bean instance to extract from; never {@code null} at call sites
     * @param fieldName the Java field or record-component name to access
     * @return the field value, or {@code null} if not accessible or not found
     */
    Object extract(T bean, String fieldName);

    /**
     * Returns the ordered list of field/component names this accessor can extract.
     *
     * <p>For generated implementations this is the complete, ordered list of annotated fields
     * or record components. The {@link ReflectiveBeanParamAccessor} fallback returns an empty
     * list because it resolves names on demand via reflection.
     *
     * @return the list of known field names; may be empty for the reflective fallback
     */
    List<String> fieldNames();
}
