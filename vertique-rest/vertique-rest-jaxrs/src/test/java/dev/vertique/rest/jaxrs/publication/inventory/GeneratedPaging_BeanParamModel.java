// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication.inventory;

import dev.vertique.rest.jaxrs.ResourceMethodMeta;
import dev.vertique.rest.jaxrs.runtime.BeanParamFieldMeta;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsBeanParamModel;
import java.lang.annotation.Annotation;
import java.util.List;

/**
 * Hand-written companion in the generated bean-param model shape for {@link GeneratedPaging}: one literal
 * {@link BeanParamFieldMeta} per bound member, in declaration order, named by the Java member name
 * and carrying its own field's runtime annotations, as generated models supply them. Found by the
 * bean-param registry through the {@code _BeanParamModel} naming convention.
 */
public final class GeneratedPaging_BeanParamModel implements GeneratedJaxRsBeanParamModel<GeneratedPaging> {

    /** No-arg constructor required for reflective instantiation by the registry. */
    public GeneratedPaging_BeanParamModel() {}

    @Override
    public Class<GeneratedPaging> beanType() {
        return GeneratedPaging.class;
    }

    @Override
    public List<BeanParamFieldMeta> fields() {
        return List.of(
                new BeanParamFieldMeta(
                        "pageSize",
                        new ResourceMethodMeta.ParamMeta(
                                "pageSize",
                                ResourceMethodMeta.ParamSource.QUERY,
                                Integer.class,
                                null,
                                null,
                                "50",
                                fieldAnnotations("pageSize"))),
                new BeanParamFieldMeta(
                        "filter",
                        new ResourceMethodMeta.ParamMeta(
                                "filter",
                                ResourceMethodMeta.ParamSource.QUERY,
                                String.class,
                                null,
                                null,
                                null,
                                fieldAnnotations("filter"))));
    }

    private static Annotation[] fieldAnnotations(String fieldName) {
        try {
            return GeneratedPaging.class.getDeclaredField(fieldName).getAnnotations();
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException("fixture field GeneratedPaging#" + fieldName, e);
        }
    }
}
