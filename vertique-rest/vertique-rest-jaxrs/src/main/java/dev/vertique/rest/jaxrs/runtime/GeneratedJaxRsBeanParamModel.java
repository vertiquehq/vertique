// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.runtime;

import java.util.List;

/**
 * SPI interface implemented by generated bean-param model companion classes.
 *
 * <p>For each {@code @BeanParam} or {@code @RequestParams} type {@code Foo} discovered by the
 * CG-010 codegen pipeline, {@code vertique-codegen-jaxrs} emits a companion
 * {@code Foo_BeanParamModel} that implements this interface. The companion is loaded at runtime
 * by {@link GeneratedJaxRsBeanParamRegistry} and consumed by {@code ParameterExtractor} in
 * place of its reflective {@code computeBeanFields} path.
 *
 * <p>This interface is intentionally <em>not sealed</em>: generated companions live in the same
 * package as their bean class, which may be in any consumer package. Using a sealed interface
 * would prevent compilation of those companions.
 *
 * <p>The {@link #fields()} list uses subclass-wins field-hiding dedup: when a subclass declares
 * a field with the same name as a superclass field, only the subclass field is included. This
 * matches JLS § 8.3 field-hiding semantics and the behaviour of the compile-time
 * {@code JaxRsBeanScanner} in {@code vertique-codegen-jaxrs}.
 *
 * @param <T> the bean class type this model was generated for
 */
public interface GeneratedJaxRsBeanParamModel<T> {

    /**
     * Returns the bean class this model was generated for.
     *
     * @return the bean type; never {@code null}
     */
    Class<T> beanType();

    /**
     * Returns the ordered list of field metadata for this bean type.
     *
     * <p>Fields are returned in declaration order (subclass fields first, then superclass
     * fields), with subclass-wins dedup applied when two fields share the same name. The list
     * is immutable.
     *
     * @return the field metadata list; never {@code null}
     */
    List<BeanParamFieldMeta> fields();
}
