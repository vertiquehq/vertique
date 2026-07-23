// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.runtime.fixture;

import dev.vertique.rest.jaxrs.runtime.BeanParamFieldMeta;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsBeanParamModel;
import java.util.List;

/**
 * Test fixture: a working generated bean-param model for {@link FixtureBean}.
 *
 * <p>The class FQN follows the algorithm from
 * {@link dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsBeanParamRegistry#derivedFqn}:
 * same package as the source class, simple name, plus the {@code _BeanParamModel} suffix.
 * Used by {@link dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsBeanParamRegistryTest} to
 * assert the happy-path lookup.
 */
public final class FixtureBean_BeanParamModel implements GeneratedJaxRsBeanParamModel<FixtureBean> {

    /** No-arg constructor required for reflective instantiation by the registry. */
    public FixtureBean_BeanParamModel() {}

    @Override
    public Class<FixtureBean> beanType() {
        return FixtureBean.class;
    }

    @Override
    public List<BeanParamFieldMeta> fields() {
        return List.of();
    }
}
