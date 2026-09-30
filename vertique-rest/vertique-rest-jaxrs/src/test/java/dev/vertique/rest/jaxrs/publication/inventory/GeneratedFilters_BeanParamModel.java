// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication.inventory;

import dev.vertique.rest.jaxrs.ResourceMethodMeta;
import dev.vertique.rest.jaxrs.runtime.BeanParamFieldMeta;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsBeanParamModel;
import java.lang.annotation.Annotation;
import java.util.List;

/**
 * Hand-written companion in the generated bean-param model shape for {@link GeneratedFilters}: one literal
 * {@link BeanParamFieldMeta} per bound member, in declaration order, named by the Java member name
 * and carrying its own field's runtime annotations, as generated models supply them. Found by the
 * bean-param registry through the {@code _BeanParamModel} naming convention.
 */
public final class GeneratedFilters_BeanParamModel implements GeneratedJaxRsBeanParamModel<GeneratedFilters> {

    /** No-arg constructor required for reflective instantiation by the registry. */
    public GeneratedFilters_BeanParamModel() {}

    @Override
    public Class<GeneratedFilters> beanType() {
        return GeneratedFilters.class;
    }

    @Override
    public List<BeanParamFieldMeta> fields() {
        return List.of(
                new BeanParamFieldMeta(
                        "limit",
                        new ResourceMethodMeta.ParamMeta(
                                "limit",
                                ResourceMethodMeta.ParamSource.QUERY,
                                Integer.class,
                                null,
                                null,
                                "20",
                                fieldAnnotations("limit"))),
                new BeanParamFieldMeta(
                        "tenant",
                        new ResourceMethodMeta.ParamMeta(
                                "X-Tenant",
                                ResourceMethodMeta.ParamSource.HEADER,
                                String.class,
                                null,
                                null,
                                null,
                                fieldAnnotations("tenant"))));
    }

    private static Annotation[] fieldAnnotations(String fieldName) {
        try {
            return GeneratedFilters.class.getDeclaredField(fieldName).getAnnotations();
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException("fixture field GeneratedFilters#" + fieldName, e);
        }
    }
}
