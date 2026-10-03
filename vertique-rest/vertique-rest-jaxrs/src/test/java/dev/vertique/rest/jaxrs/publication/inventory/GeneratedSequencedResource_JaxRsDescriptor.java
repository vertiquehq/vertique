// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication.inventory;

import static dev.vertique.rest.jaxrs.ResourceMethodMeta.ParamSource.QUERY;

import dev.vertique.rest.core.security.SecurityPolicy;
import dev.vertique.rest.core.security.SecurityPolicyViolation;
import dev.vertique.rest.jaxrs.ResourceMethodMeta;
import dev.vertique.rest.jaxrs.ResourceMethodMeta.ParamMeta;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsDescriptorSupport;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsReflectiveAnnotations;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsResourceDescriptor;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Hand-written companion in the generated descriptor shape for {@link GeneratedSequencedResource}:
 * one literal {@link ResourceMethodMeta} whose class annotations come from the effective class
 * hierarchy, so the inherited {@code @GroupSequence} is present as on the reflection path. Found by
 * the descriptor registry through the {@code _JaxRsDescriptor} naming convention.
 */
public final class GeneratedSequencedResource_JaxRsDescriptor
        implements GeneratedJaxRsResourceDescriptor<GeneratedSequencedResource> {

    private static final String RESOURCE_FQN =
            "dev.vertique.rest.jaxrs.publication.inventory.GeneratedSequencedResource";

    private static final String[] LIST_SEQUENCED_TYPES = {"java.lang.String"};

    /** No-arg constructor required for reflective instantiation by the registry. */
    public GeneratedSequencedResource_JaxRsDescriptor() {}

    @Override
    public Class<GeneratedSequencedResource> resourceType() {
        return GeneratedSequencedResource.class;
    }

    @Override
    public List<ResourceMethodMeta> describe(
            GeneratedSequencedResource resource,
            GeneratedJaxRsDescriptorSupport support,
            List<SecurityPolicyViolation> violations) {
        Method listSequenced;
        try {
            listSequenced =
                    support.resolveMethod(GeneratedSequencedResource.class, "listSequenced", LIST_SEQUENCED_TYPES);
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            throw new IllegalStateException("fixture method GeneratedSequencedResource#listSequenced", e);
        }
        List<ParamMeta> params = List.of(new ParamMeta(
                "region",
                QUERY,
                String.class,
                null,
                null,
                null,
                GeneratedJaxRsReflectiveAnnotations.mergedParameterAnnotations(
                        GeneratedSequencedResource_JaxRsDescriptor.class,
                        RESOURCE_FQN,
                        "listSequenced",
                        LIST_SEQUENCED_TYPES,
                        0)));
        return List.of(new ResourceMethodMeta(
                resource,
                listSequenced,
                "listSequenced",
                "GET",
                "/sequenced",
                params,
                String.class,
                false,
                false,
                new SecurityPolicy.None(),
                new ResourceMethodMeta.MediaTypes(List.of(), List.of("text/plain")),
                null,
                support.effectiveMethodAnnotations(listSequenced),
                support.effectiveClassAnnotations(resourceType()),
                List.of(),
                List.of()));
    }
}
