// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.runtime.fixture;

/**
 * Minimal fixture bean whose companion {@code BrokenBeanResource_BeanParamModel} throws during
 * construction. Used by
 * {@link dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsBeanParamRegistryTest} to assert that
 * the registry propagates and caches instantiation failures.
 */
public class BrokenBeanResource {}
