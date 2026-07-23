// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.runtime.fixture;

/**
 * Minimal fixture resource whose companion {@code BrokenDescriptorResource_JaxRsDescriptor}
 * throws during construction. Used by
 * {@link dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsDescriptorRegistryTest} to assert that
 * the registry propagates and caches instantiation failures.
 */
public class BrokenDescriptorResource {}
