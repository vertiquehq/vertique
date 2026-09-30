// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication.inventory;

import static dev.vertique.rest.jaxrs.ResourceMethodMeta.ParamSource.BEAN_PARAM;
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
 * Hand-written companion in the generated descriptor shape for {@link GeneratedKindsResource}: one
 * literal {@link ResourceMethodMeta} with one literal {@link ParamMeta} per method parameter, in
 * declaration order. As in generated code, no parameter carries a generic type (only a request
 * body would), so the two collection parameters report the raw {@code List}; the collection and
 * array parameters carry {@code String} as their component type. Found by the descriptor
 * registry through the {@code _JaxRsDescriptor} naming convention.
 */
public final class GeneratedKindsResource_JaxRsDescriptor
        implements GeneratedJaxRsResourceDescriptor<GeneratedKindsResource> {

    private static final String RESOURCE_FQN = "dev.vertique.rest.jaxrs.publication.inventory.GeneratedKindsResource";

    private static final String[] LIST_KINDS_TYPES = {
        "java.lang.Integer",
        "java.util.List",
        "java.util.List",
        "java.lang.String[]",
        "java.lang.String",
        "java.lang.String",
        "java.lang.String",
        "java.lang.String",
        "java.lang.String",
        "java.lang.String",
        "dev.vertique.rest.jaxrs.publication.inventory.GeneratedPaging"
    };

    /** No-arg constructor required for reflective instantiation by the registry. */
    public GeneratedKindsResource_JaxRsDescriptor() {}

    @Override
    public Class<GeneratedKindsResource> resourceType() {
        return GeneratedKindsResource.class;
    }

    @Override
    public List<ResourceMethodMeta> describe(
            GeneratedKindsResource resource,
            GeneratedJaxRsDescriptorSupport support,
            List<SecurityPolicyViolation> violations) {
        Method listKinds;
        try {
            listKinds = support.resolveMethod(GeneratedKindsResource.class, "listKinds", LIST_KINDS_TYPES);
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            throw new IllegalStateException("fixture method GeneratedKindsResource#listKinds", e);
        }
        List<ParamMeta> params = List.of(
                new ParamMeta("size", QUERY, Integer.class, null, null, "5", annotations(0)),
                new ParamMeta("tags", QUERY, List.class, String.class, null, null, annotations(1)),
                new ParamMeta("ids", QUERY, List.class, String.class, null, null, annotations(2)),
                new ParamMeta("codes", QUERY, String[].class, String.class, null, null, annotations(3)),
                new ParamMeta("name", QUERY, String.class, null, null, null, annotations(4)),
                new ParamMeta("title", QUERY, String.class, null, null, null, annotations(5)),
                new ParamMeta("both", QUERY, String.class, null, null, null, annotations(6)),
                new ParamMeta("note", QUERY, String.class, null, null, null, annotations(7)),
                new ParamMeta("zip", QUERY, String.class, null, null, null, annotations(8)),
                new ParamMeta("plain", QUERY, String.class, null, null, null, annotations(9)),
                new ParamMeta(null, BEAN_PARAM, GeneratedPaging.class, null, null, null, annotations(10)));
        return List.of(new ResourceMethodMeta(
                resource,
                listKinds,
                "listKinds",
                "GET",
                "/kinds",
                params,
                String.class,
                false,
                false,
                new SecurityPolicy.None(),
                new ResourceMethodMeta.MediaTypes(List.of(), List.of("text/plain")),
                null,
                support.effectiveMethodAnnotations(listKinds),
                support.effectiveClassAnnotations(resourceType()),
                List.of(),
                List.of()));
    }

    private static Annotation[] annotations(int index) {
        return GeneratedJaxRsReflectiveAnnotations.mergedParameterAnnotations(
                GeneratedKindsResource_JaxRsDescriptor.class, RESOURCE_FQN, "listKinds", LIST_KINDS_TYPES, index);
    }
}
