// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.metadata.it.hidden;

import dev.vertique.rest.core.security.SecurityPolicy;
import dev.vertique.rest.core.security.SecurityPolicyViolation;
import dev.vertique.rest.jaxrs.ResourceMethodMeta;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsDescriptorSupport;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsResourceDescriptor;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Hand-written companion in the generated descriptor shape for {@link
 * GeneratedHiddenContractResource}, whose annotations live on {@link GeneratedHiddenContract} (the
 * annotation processor does not run on framework test sources): one literal {@link
 * ResourceMethodMeta} for {@code GET} with no parameters, whose operation id is the {@code @Operation}
 * value, whose path is the resource path, which consumes and produces nothing declared, and whose
 * method and class annotations are the effective ones, interfaces included, as the processor would
 * emit them. Found by the descriptor registry through the {@code _JaxRsDescriptor} naming convention.
 */
public final class GeneratedHiddenContractResource_JaxRsDescriptor
        implements GeneratedJaxRsResourceDescriptor<GeneratedHiddenContractResource> {

    private static final String METHOD_NAME = "read";

    /** No-arg constructor required for reflective instantiation by the registry. */
    public GeneratedHiddenContractResource_JaxRsDescriptor() {}

    @Override
    public Class<GeneratedHiddenContractResource> resourceType() {
        return GeneratedHiddenContractResource.class;
    }

    @Override
    public List<ResourceMethodMeta> describe(
            GeneratedHiddenContractResource resource,
            GeneratedJaxRsDescriptorSupport support,
            List<SecurityPolicyViolation> violations) {
        Method read = method(support);
        return List.of(new ResourceMethodMeta(
                resource,
                read,
                GeneratedHiddenContract.OPERATION_ID,
                "GET",
                GeneratedHiddenContract.ROUTE,
                List.of(),
                Void.class,
                false,
                true,
                new SecurityPolicy.None(),
                new ResourceMethodMeta.MediaTypes(List.of(), List.of()),
                null,
                support.effectiveMethodAnnotations(read),
                support.effectiveClassAnnotations(resourceType()),
                List.of(),
                List.of()));
    }

    private static Method method(GeneratedJaxRsDescriptorSupport support) {
        try {
            return support.resolveMethod(GeneratedHiddenContractResource.class, METHOD_NAME);
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            throw new IllegalStateException("fixture method GeneratedHiddenContractResource#" + METHOD_NAME, e);
        }
    }
}
