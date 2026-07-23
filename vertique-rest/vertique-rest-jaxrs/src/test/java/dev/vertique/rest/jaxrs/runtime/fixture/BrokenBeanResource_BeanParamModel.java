// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.runtime.fixture;

import dev.vertique.rest.jaxrs.runtime.BeanParamFieldMeta;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsBeanParamModel;
import java.util.List;

/**
 * Test fixture: a broken generated bean-param model for {@link BrokenBeanResource} whose
 * constructor throws. Used by
 * {@link dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsBeanParamRegistryTest} to assert that
 * the registry propagates instantiation failures rather than silently masking them as a
 * reflective fallback.
 *
 * <p>The class FQN is derived by
 * {@link dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsBeanParamRegistry#derivedFqn}: same
 * package, simple name, plus the {@code _BeanParamModel} suffix.
 */
public final class BrokenBeanResource_BeanParamModel implements GeneratedJaxRsBeanParamModel<BrokenBeanResource> {

    /** Throws on construction so the registry's instantiation-failure path is exercised. */
    public BrokenBeanResource_BeanParamModel() {
        throw new IllegalStateException("intentional test failure during construction");
    }

    @Override
    public Class<BrokenBeanResource> beanType() {
        return BrokenBeanResource.class;
    }

    @Override
    public List<BeanParamFieldMeta> fields() {
        throw new UnsupportedOperationException("unreachable — construction always fails");
    }
}
