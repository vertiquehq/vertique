// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.runtime.fixture;

import dev.vertique.rest.core.security.SecurityPolicy;
import dev.vertique.rest.core.security.SecurityPolicyViolation;
import dev.vertique.rest.jaxrs.ResourceMethodMeta;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsDescriptorSupport;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsResourceDescriptor;
import java.util.List;

/**
 * Test fixture: generated descriptor companion for {@link SeamResource}.
 *
 * <p>Returns a single {@link ResourceMethodMeta} with {@code operationId="seam-descriptor-hit"}
 * so that {@link dev.vertique.rest.jaxrs.ResourceScannerDescriptorSeamTest} can assert the
 * fast-path was taken (a reflective walk would return a different operationId derived from the
 * actual method name {@code "hello"}).
 *
 * <p>The class FQN follows the algorithm from
 * {@link dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsDescriptorRegistry#derivedFqn}:
 * same package as the source class, simple name, plus the {@code _JaxRsDescriptor} suffix.
 */
public final class SeamResource_JaxRsDescriptor implements GeneratedJaxRsResourceDescriptor<SeamResource> {

    /** Sentinel operationId written by the descriptor — distinct from any reflective result. */
    public static final String SENTINEL_OPERATION_ID = "seam-descriptor-hit";

    /** No-arg constructor required for reflective instantiation by the registry. */
    public SeamResource_JaxRsDescriptor() {}

    @Override
    public Class<SeamResource> resourceType() {
        return SeamResource.class;
    }

    /**
     * Returns a single {@link ResourceMethodMeta} whose {@code operationId} is the sentinel
     * value {@link #SENTINEL_OPERATION_ID}. The method reference is resolved reflectively so
     * the record can be fully constructed.
     *
     * @param resource   the resource instance (unused — descriptor builds metadata statically)
     * @param support    the runtime helper bag (unused for this minimal fixture)
     * @param violations the violation sink (nothing is appended — fixture is conflict-free)
     * @return a singleton list with the sentinel metadata
     */
    @Override
    public List<ResourceMethodMeta> describe(
            SeamResource resource, GeneratedJaxRsDescriptorSupport support, List<SecurityPolicyViolation> violations) {
        try {
            java.lang.reflect.Method m = SeamResource.class.getMethod("hello");
            m.setAccessible(true);
            return List.of(new ResourceMethodMeta(
                    resource,
                    m,
                    SENTINEL_OPERATION_ID,
                    "GET",
                    "/seam/hello",
                    List.of(),
                    String.class,
                    false,
                    false,
                    new SecurityPolicy.None(),
                    new ResourceMethodMeta.MediaTypes(List.of(), List.of()),
                    null,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of()));
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("SeamResource.hello() not found — fixture mismatch", e);
        }
    }
}
