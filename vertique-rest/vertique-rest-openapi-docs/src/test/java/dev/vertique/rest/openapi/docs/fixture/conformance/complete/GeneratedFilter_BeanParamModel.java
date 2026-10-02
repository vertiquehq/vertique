// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.conformance.complete;

import dev.vertique.rest.jaxrs.ResourceMethodMeta;
import dev.vertique.rest.jaxrs.runtime.BeanParamFieldMeta;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsBeanParamModel;
import java.lang.annotation.Annotation;
import java.lang.reflect.RecordComponent;
import java.util.List;

/**
 * Hand-written companion in the generated bean-param model shape for {@link GeneratedFilter} (the
 * annotation processor does not run on framework test sources): one literal {@link
 * BeanParamFieldMeta} per bound record component, in declaration order, named by the component name
 * and carrying its accessor's runtime annotations, as generated models supply them. Found by the
 * bean-param registry through the {@code _BeanParamModel} naming convention.
 */
public final class GeneratedFilter_BeanParamModel implements GeneratedJaxRsBeanParamModel<GeneratedFilter> {

    /** No-arg constructor required for reflective instantiation by the registry. */
    public GeneratedFilter_BeanParamModel() {}

    @Override
    public Class<GeneratedFilter> beanType() {
        return GeneratedFilter.class;
    }

    @Override
    public List<BeanParamFieldMeta> fields() {
        return List.of(
                new BeanParamFieldMeta(
                        "sort",
                        new ResourceMethodMeta.ParamMeta(
                                CompleteEntries.SORT,
                                ResourceMethodMeta.ParamSource.QUERY,
                                String.class,
                                null,
                                null,
                                null,
                                componentAnnotations("sort"))),
                new BeanParamFieldMeta(
                        "locale",
                        new ResourceMethodMeta.ParamMeta(
                                CompleteEntries.LOCALE_COOKIE,
                                ResourceMethodMeta.ParamSource.COOKIE,
                                String.class,
                                null,
                                null,
                                null,
                                componentAnnotations("locale"))));
    }

    private static Annotation[] componentAnnotations(String componentName) {
        for (RecordComponent component : GeneratedFilter.class.getRecordComponents()) {
            if (component.getName().equals(componentName)) {
                return component.getAccessor().getAnnotations();
            }
        }
        throw new IllegalStateException("fixture record component GeneratedFilter#" + componentName);
    }
}
