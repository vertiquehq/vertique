// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication.inventory;

import dev.vertique.rest.jaxrs.ResourceMethodMeta;
import dev.vertique.rest.jaxrs.runtime.BeanParamFieldMeta;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsBeanParamModel;
import java.lang.annotation.Annotation;
import java.util.List;

/**
 * Hand-written companion in the generated bean-param model shape for {@link GeneratedHiddenTypeBean}: one literal
 * {@link BeanParamFieldMeta} per bound member, in declaration order, named by the Java member name
 * and carrying its own field's runtime annotations, as generated models supply them. Found by the
 * bean-param registry through the {@code _BeanParamModel} naming convention.
 */
public final class GeneratedHiddenTypeBean_BeanParamModel
        implements GeneratedJaxRsBeanParamModel<GeneratedHiddenTypeBean> {

    /** No-arg constructor required for reflective instantiation by the registry. */
    public GeneratedHiddenTypeBean_BeanParamModel() {}

    @Override
    public Class<GeneratedHiddenTypeBean> beanType() {
        return GeneratedHiddenTypeBean.class;
    }

    @Override
    public List<BeanParamFieldMeta> fields() {
        return List.of(new BeanParamFieldMeta(
                "h5",
                new ResourceMethodMeta.ParamMeta(
                        "h5",
                        ResourceMethodMeta.ParamSource.QUERY,
                        String.class,
                        null,
                        null,
                        null,
                        fieldAnnotations("h5"))));
    }

    private static Annotation[] fieldAnnotations(String fieldName) {
        try {
            return GeneratedHiddenTypeBean.class.getDeclaredField(fieldName).getAnnotations();
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException("fixture field GeneratedHiddenTypeBean#" + fieldName, e);
        }
    }
}
