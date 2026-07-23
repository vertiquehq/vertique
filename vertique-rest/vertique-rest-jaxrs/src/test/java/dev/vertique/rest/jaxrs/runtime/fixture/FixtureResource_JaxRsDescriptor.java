// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.runtime.fixture;

import dev.vertique.rest.core.security.SecurityPolicyViolation;
import dev.vertique.rest.jaxrs.ResourceMethodMeta;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsDescriptorSupport;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsResourceDescriptor;
import java.util.List;

/**
 * Test fixture: a working generated descriptor for {@link FixtureResource}.
 *
 * <p>The class FQN follows the algorithm from
 * {@link dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsDescriptorRegistry#derivedFqn}:
 * same package as the source class, simple name with {@code '$'} flattened to {@code '_'},
 * and the {@code _JaxRsDescriptor} suffix appended. Used by
 * {@link dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsDescriptorRegistryTest} to assert the
 * happy-path lookup.
 */
public final class FixtureResource_JaxRsDescriptor implements GeneratedJaxRsResourceDescriptor<FixtureResource> {

    /** No-arg constructor required for reflective instantiation by the registry. */
    public FixtureResource_JaxRsDescriptor() {}

    @Override
    public Class<FixtureResource> resourceType() {
        return FixtureResource.class;
    }

    @Override
    public List<ResourceMethodMeta> describe(
            FixtureResource resource,
            GeneratedJaxRsDescriptorSupport support,
            List<SecurityPolicyViolation> violations) {
        return List.of();
    }
}
