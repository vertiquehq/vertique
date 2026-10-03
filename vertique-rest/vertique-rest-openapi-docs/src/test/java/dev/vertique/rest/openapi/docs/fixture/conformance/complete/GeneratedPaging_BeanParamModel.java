// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.conformance.complete;

import dev.vertique.rest.jaxrs.ResourceMethodMeta;
import dev.vertique.rest.jaxrs.runtime.BeanParamFieldMeta;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsBeanParamModel;
import java.lang.annotation.Annotation;
import java.util.List;

/**
 * Hand-written companion in the generated bean-param model shape for {@link GeneratedPaging} (the
 * annotation processor does not run on framework test sources): one literal {@link
 * BeanParamFieldMeta} per bound field, in declaration order, named by the Java field name and
 * carrying that field's runtime annotations, as generated models supply them. Found by the
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
                        "page",
                        new ResourceMethodMeta.ParamMeta(
                                CompleteEntries.PAGE,
                                ResourceMethodMeta.ParamSource.QUERY,
                                int.class,
                                null,
                                null,
                                CompleteEntries.PAGE_DEFAULT,
                                fieldAnnotations("page"))),
                new BeanParamFieldMeta(
                        "pageSize",
                        new ResourceMethodMeta.ParamMeta(
                                CompleteEntries.PAGE_SIZE_HEADER,
                                ResourceMethodMeta.ParamSource.HEADER,
                                String.class,
                                null,
                                null,
                                null,
                                fieldAnnotations("pageSize"))));
    }

    private static Annotation[] fieldAnnotations(String fieldName) {
        try {
            return GeneratedPaging.class.getDeclaredField(fieldName).getAnnotations();
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException("fixture field GeneratedPaging#" + fieldName, e);
        }
    }
}
