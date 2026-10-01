// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.input.it;

import dev.vertique.rest.jaxrs.ResourceMethodMeta;
import dev.vertique.rest.jaxrs.runtime.BeanParamFieldMeta;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsBeanParamModel;
import java.lang.annotation.Annotation;
import java.util.List;

/**
 * Hand-written companion in the generated bean-param model shape for {@link GeneratedSearchFilters}
 * (the annotation processor does not run on framework test sources): one literal {@link
 * BeanParamFieldMeta} per bound field, in declaration order, named by the Java field name and
 * carrying that field's runtime annotations, as generated models supply them. Found by the
 * bean-param registry through the {@code _BeanParamModel} naming convention.
 */
public final class GeneratedSearchFilters_BeanParamModel
        implements GeneratedJaxRsBeanParamModel<GeneratedSearchFilters> {

    /** No-arg constructor required for reflective instantiation by the registry. */
    public GeneratedSearchFilters_BeanParamModel() {}

    @Override
    public Class<GeneratedSearchFilters> beanType() {
        return GeneratedSearchFilters.class;
    }

    @Override
    public List<BeanParamFieldMeta> fields() {
        return List.of(
                new BeanParamFieldMeta(
                        "q",
                        new ResourceMethodMeta.ParamMeta(
                                "q",
                                ResourceMethodMeta.ParamSource.QUERY,
                                String.class,
                                null,
                                null,
                                null,
                                fieldAnnotations("q"))),
                new BeanParamFieldMeta(
                        "tenant",
                        new ResourceMethodMeta.ParamMeta(
                                TwinInputs.TENANT_HEADER,
                                ResourceMethodMeta.ParamSource.HEADER,
                                String.class,
                                null,
                                null,
                                null,
                                fieldAnnotations("tenant"))),
                new BeanParamFieldMeta(
                        "limit",
                        new ResourceMethodMeta.ParamMeta(
                                "limit",
                                ResourceMethodMeta.ParamSource.QUERY,
                                int.class,
                                null,
                                null,
                                TwinInputs.LIMIT_DEFAULT,
                                fieldAnnotations("limit"))));
    }

    private static Annotation[] fieldAnnotations(String fieldName) {
        try {
            return GeneratedSearchFilters.class.getDeclaredField(fieldName).getAnnotations();
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException("fixture field GeneratedSearchFilters#" + fieldName, e);
        }
    }
}
