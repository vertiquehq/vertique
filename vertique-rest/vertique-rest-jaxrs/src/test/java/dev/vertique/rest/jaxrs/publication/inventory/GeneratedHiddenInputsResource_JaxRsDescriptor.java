// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication.inventory;

import static dev.vertique.rest.jaxrs.ResourceMethodMeta.ParamSource.BEAN_PARAM;
import static dev.vertique.rest.jaxrs.ResourceMethodMeta.ParamSource.HEADER;
import static dev.vertique.rest.jaxrs.ResourceMethodMeta.ParamSource.QUERY;

import dev.vertique.rest.core.security.SecurityPolicy;
import dev.vertique.rest.core.security.SecurityPolicyViolation;
import dev.vertique.rest.jaxrs.ResourceMethodMeta;
import dev.vertique.rest.jaxrs.ResourceMethodMeta.ParamMeta;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsDescriptorSupport;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsReflectiveAnnotations;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsResourceDescriptor;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Hand-written companion in the generated descriptor shape for {@link GeneratedHiddenInputsResource}:
 * one literal {@link ResourceMethodMeta} with one literal {@link ParamMeta} per method parameter, in
 * declaration order, each carrying the parameter's merged runtime-retained annotations the way
 * generated code supplies them. Found by the descriptor registry through the
 * {@code _JaxRsDescriptor} naming convention.
 */
public final class GeneratedHiddenInputsResource_JaxRsDescriptor
        implements GeneratedJaxRsResourceDescriptor<GeneratedHiddenInputsResource> {

    private static final String RESOURCE_FQN =
            "dev.vertique.rest.jaxrs.publication.inventory.GeneratedHiddenInputsResource";

    private static final String[] LIST_HIDDEN_TYPES = {
        "java.lang.String",
        "java.lang.String",
        "java.lang.String",
        "dev.vertique.rest.jaxrs.publication.inventory.GeneratedHiddenFieldBean",
        "dev.vertique.rest.jaxrs.publication.inventory.GeneratedHiddenComponentParams",
        "dev.vertique.rest.jaxrs.publication.inventory.GeneratedPropagatedBean",
        "dev.vertique.rest.jaxrs.publication.inventory.GeneratedPropagatedParams",
        "dev.vertique.rest.jaxrs.publication.inventory.GeneratedHiddenTypeBean",
        "dev.vertique.rest.jaxrs.publication.inventory.GeneratedHiddenTypeParams"
    };

    /** No-arg constructor required for reflective instantiation by the registry. */
    public GeneratedHiddenInputsResource_JaxRsDescriptor() {}

    @Override
    public Class<GeneratedHiddenInputsResource> resourceType() {
        return GeneratedHiddenInputsResource.class;
    }

    @Override
    public List<ResourceMethodMeta> describe(
            GeneratedHiddenInputsResource resource,
            GeneratedJaxRsDescriptorSupport support,
            List<SecurityPolicyViolation> violations) {
        Method listHidden;
        try {
            listHidden = support.resolveMethod(GeneratedHiddenInputsResource.class, "listHidden", LIST_HIDDEN_TYPES);
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            throw new IllegalStateException("fixture method GeneratedHiddenInputsResource#listHidden", e);
        }
        List<ParamMeta> params = List.of(
                new ParamMeta("debug", QUERY, String.class, null, null, null, annotations(0)),
                new ParamMeta("verbose", QUERY, String.class, null, null, null, annotations(1)),
                new ParamMeta("X-Internal", HEADER, String.class, null, null, null, annotations(2)),
                new ParamMeta(null, BEAN_PARAM, GeneratedHiddenFieldBean.class, null, null, null, annotations(3)),
                new ParamMeta(null, BEAN_PARAM, GeneratedHiddenComponentParams.class, null, null, null, annotations(4)),
                new ParamMeta(null, BEAN_PARAM, GeneratedPropagatedBean.class, null, null, null, annotations(5)),
                new ParamMeta(null, BEAN_PARAM, GeneratedPropagatedParams.class, null, null, null, annotations(6)),
                new ParamMeta(null, BEAN_PARAM, GeneratedHiddenTypeBean.class, null, null, null, annotations(7)),
                new ParamMeta(null, BEAN_PARAM, GeneratedHiddenTypeParams.class, null, null, null, annotations(8)));
        return List.of(new ResourceMethodMeta(
                resource,
                listHidden,
                "listHidden",
                "GET",
                "/hidden",
                params,
                String.class,
                false,
                false,
                new SecurityPolicy.None(),
                new ResourceMethodMeta.MediaTypes(List.of(), List.of("text/plain")),
                null,
                support.effectiveMethodAnnotations(listHidden),
                support.effectiveClassAnnotations(resourceType()),
                List.of(),
                List.of()));
    }

    private static Annotation[] annotations(int index) {
        return GeneratedJaxRsReflectiveAnnotations.mergedParameterAnnotations(
                GeneratedHiddenInputsResource_JaxRsDescriptor.class,
                RESOURCE_FQN,
                "listHidden",
                LIST_HIDDEN_TYPES,
                index);
    }
}
