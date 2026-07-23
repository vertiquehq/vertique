// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.runtime.fixture;

import dev.vertique.rest.core.security.SecurityPolicyViolation;
import dev.vertique.rest.jaxrs.ResourceMethodMeta;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsDescriptorSupport;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsResourceDescriptor;
import java.util.List;

/**
 * Test fixture: a broken generated descriptor for {@link BrokenDescriptorResource} whose
 * constructor throws. Used by
 * {@link dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsDescriptorRegistryTest} to assert that
 * the registry propagates instantiation failures rather than silently masking them as a
 * reflective fallback.
 *
 * <p>The class FQN is derived by the registry's
 * {@link dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsDescriptorRegistry#derivedFqn} algorithm:
 * same package, simple name, plus the {@code _JaxRsDescriptor} suffix.
 */
public final class BrokenDescriptorResource_JaxRsDescriptor
        implements GeneratedJaxRsResourceDescriptor<BrokenDescriptorResource> {

    /** Throws on construction so the registry's instantiation-failure path is exercised. */
    public BrokenDescriptorResource_JaxRsDescriptor() {
        throw new IllegalStateException("intentional test failure during construction");
    }

    @Override
    public Class<BrokenDescriptorResource> resourceType() {
        return BrokenDescriptorResource.class;
    }

    @Override
    public List<ResourceMethodMeta> describe(
            BrokenDescriptorResource resource,
            GeneratedJaxRsDescriptorSupport support,
            List<SecurityPolicyViolation> violations) {
        throw new UnsupportedOperationException("unreachable — construction always fails");
    }
}
