// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.runtime.fixture;

import dev.vertique.rest.jaxrs.ResourceMethodMeta;
import dev.vertique.rest.jaxrs.runtime.BeanParamFieldMeta;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsBeanParamModel;
import java.util.List;

/**
 * Test fixture: generated bean-param model companion for {@link SeamBean}.
 *
 * <p>Returns a single {@link BeanParamFieldMeta} whose {@code name} is the sentinel value
 * {@link #SENTINEL_FIELD_NAME} so that
 * {@link dev.vertique.rest.jaxrs.ParameterExtractorBeanParamSeamTest} can assert the fast-path
 * was taken (a reflective walk would return the real field name {@code "filter"}).
 *
 * <p>The class FQN follows the algorithm from
 * {@link dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsBeanParamRegistry#derivedFqn}:
 * same package as the source class, simple name, plus the {@code _BeanParamModel} suffix.
 */
public final class SeamBean_BeanParamModel implements GeneratedJaxRsBeanParamModel<SeamBean> {

    /** Sentinel field name returned by the model — distinct from any reflective result. */
    public static final String SENTINEL_FIELD_NAME = "seam-model-hit";

    /** No-arg constructor required for reflective instantiation by the registry. */
    public SeamBean_BeanParamModel() {}

    @Override
    public Class<SeamBean> beanType() {
        return SeamBean.class;
    }

    /**
     * Returns a single {@link BeanParamFieldMeta} whose {@code name} is the sentinel value
     * {@link #SENTINEL_FIELD_NAME}.
     *
     * @return a singleton list with the sentinel metadata
     */
    @Override
    public List<BeanParamFieldMeta> fields() {
        return List.of(new BeanParamFieldMeta(
                SENTINEL_FIELD_NAME,
                new ResourceMethodMeta.ParamMeta("filter", ResourceMethodMeta.ParamSource.QUERY, String.class)));
    }
}
