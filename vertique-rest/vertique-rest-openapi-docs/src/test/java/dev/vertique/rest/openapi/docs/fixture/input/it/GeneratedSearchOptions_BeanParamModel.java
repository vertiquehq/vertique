// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.input.it;

import dev.vertique.rest.jaxrs.ResourceMethodMeta;
import dev.vertique.rest.jaxrs.runtime.BeanParamFieldMeta;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsBeanParamModel;
import java.lang.annotation.Annotation;
import java.lang.reflect.RecordComponent;
import java.util.List;

/**
 * Hand-written companion in the generated bean-param model shape for {@link GeneratedSearchOptions}
 * (the annotation processor does not run on framework test sources): one literal {@link
 * BeanParamFieldMeta} per bound record component, in declaration order, named by the component name
 * and carrying its accessor's runtime annotations, as generated models supply them. Found by the
 * bean-param registry through the {@code _BeanParamModel} naming convention.
 */
public final class GeneratedSearchOptions_BeanParamModel
        implements GeneratedJaxRsBeanParamModel<GeneratedSearchOptions> {

    /** No-arg constructor required for reflective instantiation by the registry. */
    public GeneratedSearchOptions_BeanParamModel() {}

    @Override
    public Class<GeneratedSearchOptions> beanType() {
        return GeneratedSearchOptions.class;
    }

    @Override
    public List<BeanParamFieldMeta> fields() {
        return List.of(new BeanParamFieldMeta(
                "sort",
                new ResourceMethodMeta.ParamMeta(
                        "sort",
                        ResourceMethodMeta.ParamSource.QUERY,
                        String.class,
                        null,
                        null,
                        null,
                        componentAnnotations("sort"))));
    }

    private static Annotation[] componentAnnotations(String componentName) {
        for (RecordComponent component : GeneratedSearchOptions.class.getRecordComponents()) {
            if (component.getName().equals(componentName)) {
                return component.getAccessor().getAnnotations();
            }
        }
        throw new IllegalStateException("fixture record component GeneratedSearchOptions#" + componentName);
    }
}
