// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.runtime;

import dev.vertique.rest.core.security.SecurityPolicyViolation;
import dev.vertique.rest.jaxrs.ResourceMethodMeta;
import java.util.List;

/**
 * SPI interface implemented by generated JAX-RS resource descriptor companion classes.
 *
 * <p>For each concrete resource class {@code Foo} discovered by the codegen pipeline,
 * {@code vertique-codegen-jaxrs} emits a companion {@code Foo_JaxRsDescriptor} that implements
 * this interface. The companion is loaded at runtime by {@link GeneratedJaxRsDescriptorRegistry}
 * and used by {@code JaxRsRouteRegistrar} in place of the reflective {@code ResourceScanner}.
 *
 * <p>This interface is intentionally <em>not sealed</em>: generated companions live in arbitrary
 * consumer packages (same package as their resource class), which are not visible from
 * {@code rest-jaxrs}. Using a sealed interface here would prevent compilation of those companions.
 *
 * <h2>violations parameter</h2>
 *
 * <p>The {@code violations} list mirrors the shape of
 * {@code ResourceScanner.scanResource(Object, List<SecurityPolicyViolation>)}'s sink parameter.
 * Generated descriptors produced by the CG-010 pipeline <strong>never append to this list</strong>
 * because the {@code SecurityAnnotationValidator} (step 3 in the pipeline) rejects
 * {@code CONFLICTING_SECURITY_ANNOTATIONS} and {@code EMPTY_ROLES_ALLOWED} at compile time.
 * The sink is retained in the SPI for forward-compatibility with potential non-pipeline
 * (hand-authored or tooling-generated) descriptors that do not have full compile-time validation
 * coverage.
 *
 * @param <T> the resource class type this descriptor describes
 */
public interface GeneratedJaxRsResourceDescriptor<T> {

    /**
     * Returns the resource class this descriptor was generated for.
     *
     * @return the resource type; never {@code null}
     */
    Class<T> resourceType();

    /**
     * Builds the {@link ResourceMethodMeta} list for the given resource instance.
     *
     * <p>Implementations resolve types, methods, and annotations via {@code support} and return one
     * {@link ResourceMethodMeta} per discovered resource method. The returned list is consumed by
     * {@code JaxRsRouteRegistrar} to register Vert.x routes.
     *
     * <p>The {@code violations} list is provided as a write sink so callers that validate multiple
     * resources can collect all violations before failing. Generated descriptors produced by the
     * standard pipeline do not append to it (see class-level note above), but the parameter must be
     * accepted by all implementations for SPI compatibility.
     *
     * @param resource   the resource instance to describe; its class must be {@code == resourceType()}
     * @param support    the runtime helper bag for type resolution and annotation merging; never
     *                   {@code null}
     * @param violations mutable list into which security-policy violations MAY be appended; never
     *                   {@code null}
     * @return ordered list of resource method metadata; never {@code null}
     */
    List<ResourceMethodMeta> describe(
            T resource, GeneratedJaxRsDescriptorSupport support, List<SecurityPolicyViolation> violations);
}
