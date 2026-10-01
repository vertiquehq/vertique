// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.metadata.it.enrichment;

import static dev.vertique.rest.jaxrs.ResourceMethodMeta.ParamSource.BODY;
import static dev.vertique.rest.jaxrs.ResourceMethodMeta.ParamSource.QUERY;

import dev.vertique.rest.core.security.SecurityPolicy;
import dev.vertique.rest.core.security.SecurityPolicyViolation;
import dev.vertique.rest.jaxrs.ResourceMethodMeta;
import dev.vertique.rest.jaxrs.ResourceMethodMeta.ParamMeta;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsDescriptorSupport;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsReflectiveAnnotations;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsResourceDescriptor;
import dev.vertique.rest.openapi.docs.fixture.metadata.dto.ItemDto;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Hand-written companion in the generated descriptor shape for {@link GeneratedCatalogResource} (the
 * annotation processor does not run on framework test sources): one literal {@link
 * ResourceMethodMeta} per operation whose {@link ParamMeta}s (name, source, type, component type,
 * generic type, default value) follow the method's parameters in declaration order, each with its
 * merged runtime-retained annotations supplied the way generated code supplies them; as in generated
 * code, only the request body carries a generic type. The operation id is the {@code @Operation}
 * value, the path is the resource path, the {@code POST} consumes {@code application/json}, and
 * neither operation declares what it produces, as the processor would emit them. Found by the
 * descriptor registry through the {@code _JaxRsDescriptor} naming convention.
 */
public final class GeneratedCatalogResource_JaxRsDescriptor
        implements GeneratedJaxRsResourceDescriptor<GeneratedCatalogResource> {

    private static final String RESOURCE_FQN = GeneratedCatalogResource.class.getName();

    private static final String LIST_ITEMS_METHOD = "listItems";

    private static final String CREATE_ITEM_METHOD = "createItem";

    private static final String[] LIST_ITEMS_TYPES = {"java.lang.String"};

    private static final String[] CREATE_ITEM_TYPES = {ItemDto.class.getName()};

    private static final String APPLICATION_JSON = "application/json";

    /** No-arg constructor required for reflective instantiation by the registry. */
    public GeneratedCatalogResource_JaxRsDescriptor() {}

    @Override
    public Class<GeneratedCatalogResource> resourceType() {
        return GeneratedCatalogResource.class;
    }

    @Override
    public List<ResourceMethodMeta> describe(
            GeneratedCatalogResource resource,
            GeneratedJaxRsDescriptorSupport support,
            List<SecurityPolicyViolation> violations) {
        List<Annotation> classAnnotations = support.effectiveClassAnnotations(resourceType());

        Method listItems = method(support, LIST_ITEMS_METHOD, LIST_ITEMS_TYPES);
        List<ParamMeta> listItemsParams = List.of(new ParamMeta(
                "limit", QUERY, String.class, null, null, null, annotations(LIST_ITEMS_METHOD, LIST_ITEMS_TYPES, 0)));

        Method createItem = method(support, CREATE_ITEM_METHOD, CREATE_ITEM_TYPES);
        List<ParamMeta> createItemParams = List.of(new ParamMeta(
                null,
                BODY,
                ItemDto.class,
                null,
                ItemDto.class,
                null,
                annotations(CREATE_ITEM_METHOD, CREATE_ITEM_TYPES, 0)));

        return List.of(
                meta(
                        resource,
                        support,
                        classAnnotations,
                        listItems,
                        CatalogOperations.LIST_ITEMS,
                        "GET",
                        listItemsParams,
                        List.of()),
                meta(
                        resource,
                        support,
                        classAnnotations,
                        createItem,
                        CatalogOperations.CREATE_ITEM,
                        "POST",
                        createItemParams,
                        List.of(APPLICATION_JSON)));
    }

    private static ResourceMethodMeta meta(
            GeneratedCatalogResource resource,
            GeneratedJaxRsDescriptorSupport support,
            List<Annotation> classAnnotations,
            Method method,
            String operationId,
            String httpMethod,
            List<ParamMeta> params,
            List<String> consumes) {
        return new ResourceMethodMeta(
                resource,
                method,
                operationId,
                httpMethod,
                CatalogOperations.RESOURCE_PATH,
                params,
                Void.class,
                false,
                true,
                new SecurityPolicy.None(),
                new ResourceMethodMeta.MediaTypes(consumes, List.of()),
                null,
                support.effectiveMethodAnnotations(method),
                classAnnotations,
                List.of(),
                List.of());
    }

    private static Method method(GeneratedJaxRsDescriptorSupport support, String name, String[] parameterTypes) {
        try {
            return support.resolveMethod(GeneratedCatalogResource.class, name, parameterTypes);
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            throw new IllegalStateException("fixture method GeneratedCatalogResource#" + name, e);
        }
    }

    private static Annotation[] annotations(String methodName, String[] parameterTypes, int index) {
        return GeneratedJaxRsReflectiveAnnotations.mergedParameterAnnotations(
                GeneratedCatalogResource_JaxRsDescriptor.class, RESOURCE_FQN, methodName, parameterTypes, index);
    }
}
