// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.input.it;

import static dev.vertique.rest.jaxrs.ResourceMethodMeta.ParamSource.BEAN_PARAM;
import static dev.vertique.rest.jaxrs.ResourceMethodMeta.ParamSource.PATH;
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
 * Hand-written companion in the generated descriptor shape for {@link GeneratedSearchResource} (the
 * annotation processor does not run on framework test sources): one literal {@link
 * ResourceMethodMeta} whose {@link ParamMeta}s (name, source, type, component type, generic type,
 * default value) follow the method's parameters in declaration order, each with its merged
 * runtime-retained annotations supplied the way generated code supplies them. The operation id is
 * the {@code @Operation} value, the path joins the resource and method paths, and the operation
 * consumes and produces nothing declared, as the processor would emit them. Found by the descriptor
 * registry through the {@code _JaxRsDescriptor} naming convention.
 */
public final class GeneratedSearchResource_JaxRsDescriptor
        implements GeneratedJaxRsResourceDescriptor<GeneratedSearchResource> {

    private static final String RESOURCE_FQN = GeneratedSearchResource.class.getName();

    private static final String METHOD_NAME = "search";

    private static final String[] SEARCH_TYPES = {
        "java.lang.String",
        GeneratedSearchFilters.class.getName(),
        "java.lang.String",
        "boolean",
        GeneratedSearchOptions.class.getName(),
        "int"
    };

    /** No-arg constructor required for reflective instantiation by the registry. */
    public GeneratedSearchResource_JaxRsDescriptor() {}

    @Override
    public Class<GeneratedSearchResource> resourceType() {
        return GeneratedSearchResource.class;
    }

    @Override
    public List<ResourceMethodMeta> describe(
            GeneratedSearchResource resource,
            GeneratedJaxRsDescriptorSupport support,
            List<SecurityPolicyViolation> violations) {
        List<Annotation> classAnnotations = support.effectiveClassAnnotations(resourceType());
        Method search = method(support);
        List<ParamMeta> params = List.of(
                new ParamMeta("id", PATH, String.class, null, null, null, annotations(0)),
                new ParamMeta(null, BEAN_PARAM, GeneratedSearchFilters.class, null, null, null, annotations(1)),
                new ParamMeta("sku", QUERY, String.class, null, null, null, annotations(2)),
                new ParamMeta("verbose", QUERY, boolean.class, null, null, null, annotations(3)),
                new ParamMeta(null, BEAN_PARAM, GeneratedSearchOptions.class, null, null, null, annotations(4)),
                new ParamMeta("page", QUERY, int.class, null, null, TwinInputs.PAGE_DEFAULT, annotations(5)));
        return List.of(new ResourceMethodMeta(
                resource,
                search,
                GeneratedSearchResource.OPERATION_ID,
                "GET",
                TwinInputs.ROUTE,
                params,
                Void.class,
                false,
                true,
                new SecurityPolicy.None(),
                new ResourceMethodMeta.MediaTypes(List.of(), List.of()),
                null,
                support.effectiveMethodAnnotations(search),
                classAnnotations,
                List.of(),
                List.of()));
    }

    private static Method method(GeneratedJaxRsDescriptorSupport support) {
        try {
            return support.resolveMethod(GeneratedSearchResource.class, METHOD_NAME, SEARCH_TYPES);
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            throw new IllegalStateException("fixture method GeneratedSearchResource#" + METHOD_NAME, e);
        }
    }

    private static Annotation[] annotations(int index) {
        return GeneratedJaxRsReflectiveAnnotations.mergedParameterAnnotations(
                GeneratedSearchResource_JaxRsDescriptor.class, RESOURCE_FQN, METHOD_NAME, SEARCH_TYPES, index);
    }
}
