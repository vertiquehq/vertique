// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication.inventory;

import dev.vertique.rest.jaxrs.ResourceMethodMeta;
import dev.vertique.rest.jaxrs.runtime.BeanParamFieldMeta;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsBeanParamModel;
import java.lang.annotation.Annotation;
import java.lang.reflect.RecordComponent;
import java.util.List;

/**
 * Hand-written companion in the generated bean-param model shape for {@link GeneratedSearchParams}: one literal
 * {@link BeanParamFieldMeta} per bound member, in declaration order, named by the Java member name
 * and carrying its own record component accessor's runtime annotations, as generated models supply them. Found by the
 * bean-param registry through the {@code _BeanParamModel} naming convention.
 */
public final class GeneratedSearchParams_BeanParamModel implements GeneratedJaxRsBeanParamModel<GeneratedSearchParams> {

    /** No-arg constructor required for reflective instantiation by the registry. */
    public GeneratedSearchParams_BeanParamModel() {}

    @Override
    public Class<GeneratedSearchParams> beanType() {
        return GeneratedSearchParams.class;
    }

    @Override
    public List<BeanParamFieldMeta> fields() {
        return List.of(
                new BeanParamFieldMeta(
                        "sort",
                        new ResourceMethodMeta.ParamMeta(
                                "sort",
                                ResourceMethodMeta.ParamSource.QUERY,
                                String.class,
                                null,
                                null,
                                null,
                                componentAnnotations("sort"))),
                new BeanParamFieldMeta(
                        "page",
                        new ResourceMethodMeta.ParamMeta(
                                "page",
                                ResourceMethodMeta.ParamSource.QUERY,
                                int.class,
                                null,
                                null,
                                "1",
                                componentAnnotations("page"))),
                new BeanParamFieldMeta(
                        "owner",
                        new ResourceMethodMeta.ParamMeta(
                                "owner",
                                ResourceMethodMeta.ParamSource.QUERY,
                                String.class,
                                null,
                                null,
                                null,
                                componentAnnotations("owner"))));
    }

    private static Annotation[] componentAnnotations(String componentName) {
        for (RecordComponent component : GeneratedSearchParams.class.getRecordComponents()) {
            if (component.getName().equals(componentName)) {
                return component.getAccessor().getAnnotations();
            }
        }
        throw new IllegalStateException("fixture record component GeneratedSearchParams#" + componentName);
    }
}
