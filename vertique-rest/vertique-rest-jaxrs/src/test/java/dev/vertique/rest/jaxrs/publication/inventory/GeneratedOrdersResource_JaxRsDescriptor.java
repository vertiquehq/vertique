// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication.inventory;

import static dev.vertique.rest.jaxrs.ResourceMethodMeta.ParamSource.BEAN_PARAM;
import static dev.vertique.rest.jaxrs.ResourceMethodMeta.ParamSource.BODY;
import static dev.vertique.rest.jaxrs.ResourceMethodMeta.ParamSource.CONTEXT;
import static dev.vertique.rest.jaxrs.ResourceMethodMeta.ParamSource.COOKIE;
import static dev.vertique.rest.jaxrs.ResourceMethodMeta.ParamSource.FORM;
import static dev.vertique.rest.jaxrs.ResourceMethodMeta.ParamSource.HEADER;
import static dev.vertique.rest.jaxrs.ResourceMethodMeta.ParamSource.PATH;
import static dev.vertique.rest.jaxrs.ResourceMethodMeta.ParamSource.QUERY;

import dev.vertique.rest.core.security.SecurityPolicy;
import dev.vertique.rest.core.security.SecurityPolicyViolation;
import dev.vertique.rest.jaxrs.ResourceMethodMeta;
import dev.vertique.rest.jaxrs.ResourceMethodMeta.ParamMeta;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsDescriptorSupport;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsReflectiveAnnotations;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsResourceDescriptor;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.Nullable;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Hand-written companion in the generated descriptor shape for {@link GeneratedOrdersResource}:
 * every {@link ResourceMethodMeta} and {@link ParamMeta} is a literal construction (name, source,
 * type, component type, generic type, default value), one per method parameter in declaration
 * order, with each parameter's merged runtime-retained annotations supplied the way generated code
 * supplies them. As in generated code, only the request body carries a generic type. Found by the
 * descriptor registry through the {@code _JaxRsDescriptor} naming convention.
 */
public final class GeneratedOrdersResource_JaxRsDescriptor
        implements GeneratedJaxRsResourceDescriptor<GeneratedOrdersResource> {

    private static final String RESOURCE_FQN = "dev.vertique.rest.jaxrs.publication.inventory.GeneratedOrdersResource";

    private static final String[] CREATE_ORDER_TYPES = {
        "java.lang.String",
        "int",
        "java.lang.String",
        "java.lang.String",
        "io.vertx.ext.web.RoutingContext",
        "dev.vertique.rest.jaxrs.publication.inventory.GeneratedFilters",
        "dev.vertique.rest.jaxrs.publication.inventory.Order",
        "dev.vertique.rest.jaxrs.publication.inventory.GeneratedSearchParams",
        "java.lang.String",
        "java.lang.String"
    };

    private static final String[] ADD_NOTE_TYPES = {"java.lang.String"};

    private static final String[] UPDATE_AUDIT_TYPES = {"java.lang.String", "java.lang.String", "java.lang.String"};

    private static final String[] LIST_CONVERSIONS_TYPES = {
        "dev.vertique.rest.jaxrs.publication.inventory.GeneratedConversions", "java.lang.String"
    };

    private static final String TEXT_PLAIN = "text/plain";

    /** No-arg constructor required for reflective instantiation by the registry. */
    public GeneratedOrdersResource_JaxRsDescriptor() {}

    @Override
    public Class<GeneratedOrdersResource> resourceType() {
        return GeneratedOrdersResource.class;
    }

    @Override
    public List<ResourceMethodMeta> describe(
            GeneratedOrdersResource resource,
            GeneratedJaxRsDescriptorSupport support,
            List<SecurityPolicyViolation> violations) {
        List<Annotation> classAnnotations = support.effectiveClassAnnotations(resourceType());

        Method createOrder = method(support, "createOrder", CREATE_ORDER_TYPES);
        List<ParamMeta> createOrderParams = List.of(
                new ParamMeta(
                        "id", PATH, String.class, null, null, null, annotations("createOrder", CREATE_ORDER_TYPES, 0)),
                new ParamMeta(
                        "q", QUERY, int.class, null, null, "10", annotations("createOrder", CREATE_ORDER_TYPES, 1)),
                new ParamMeta(
                        "X-Trace",
                        HEADER,
                        String.class,
                        null,
                        null,
                        null,
                        annotations("createOrder", CREATE_ORDER_TYPES, 2)),
                new ParamMeta(
                        "session",
                        COOKIE,
                        String.class,
                        null,
                        null,
                        null,
                        annotations("createOrder", CREATE_ORDER_TYPES, 3)),
                new ParamMeta(
                        null,
                        CONTEXT,
                        RoutingContext.class,
                        null,
                        null,
                        null,
                        annotations("createOrder", CREATE_ORDER_TYPES, 4)),
                new ParamMeta(
                        null,
                        BEAN_PARAM,
                        GeneratedFilters.class,
                        null,
                        null,
                        null,
                        annotations("createOrder", CREATE_ORDER_TYPES, 5)),
                new ParamMeta(
                        null,
                        BODY,
                        Order.class,
                        null,
                        Order.class,
                        null,
                        annotations("createOrder", CREATE_ORDER_TYPES, 6)),
                new ParamMeta(
                        null,
                        BEAN_PARAM,
                        GeneratedSearchParams.class,
                        null,
                        null,
                        null,
                        annotations("createOrder", CREATE_ORDER_TYPES, 7)),
                new ParamMeta(
                        "region",
                        QUERY,
                        String.class,
                        null,
                        null,
                        null,
                        annotations("createOrder", CREATE_ORDER_TYPES, 8)),
                new ParamMeta(
                        "audit",
                        QUERY,
                        String.class,
                        null,
                        null,
                        null,
                        annotations("createOrder", CREATE_ORDER_TYPES, 9)));

        Method addNote = method(support, "addNote", ADD_NOTE_TYPES);
        List<ParamMeta> addNoteParams = List.of(
                new ParamMeta("note", FORM, String.class, null, null, null, annotations("addNote", ADD_NOTE_TYPES, 0)));

        Method updateAudit = method(support, "updateAudit", UPDATE_AUDIT_TYPES);
        List<ParamMeta> updateAuditParams = List.of(
                new ParamMeta(
                        "id", PATH, String.class, null, null, null, annotations("updateAudit", UPDATE_AUDIT_TYPES, 0)),
                new ParamMeta(
                        "audit",
                        QUERY,
                        String.class,
                        null,
                        null,
                        null,
                        annotations("updateAudit", UPDATE_AUDIT_TYPES, 1)),
                new ParamMeta(
                        "region",
                        QUERY,
                        String.class,
                        null,
                        null,
                        null,
                        annotations("updateAudit", UPDATE_AUDIT_TYPES, 2)));

        Method listConversions = method(support, "listConversions", LIST_CONVERSIONS_TYPES);
        List<ParamMeta> listConversionsParams = List.of(
                new ParamMeta(
                        null,
                        BEAN_PARAM,
                        GeneratedConversions.class,
                        null,
                        null,
                        null,
                        annotations("listConversions", LIST_CONVERSIONS_TYPES, 0)),
                new ParamMeta(
                        "channel",
                        QUERY,
                        String.class,
                        null,
                        null,
                        null,
                        annotations("listConversions", LIST_CONVERSIONS_TYPES, 1)));

        return List.of(
                meta(
                        resource,
                        support,
                        classAnnotations,
                        createOrder,
                        "POST",
                        "/orders/{id}",
                        createOrderParams,
                        List.of(),
                        null),
                meta(
                        resource,
                        support,
                        classAnnotations,
                        addNote,
                        "POST",
                        "/notes",
                        addNoteParams,
                        List.of("application/x-www-form-urlencoded"),
                        null),
                meta(
                        resource,
                        support,
                        classAnnotations,
                        updateAudit,
                        "PUT",
                        "/audits/{id}",
                        updateAuditParams,
                        List.of(),
                        new Class<?>[] {StrictAudit.class}),
                meta(
                        resource,
                        support,
                        classAnnotations,
                        listConversions,
                        "GET",
                        "/conversions",
                        listConversionsParams,
                        List.of(),
                        null));
    }

    private static ResourceMethodMeta meta(
            GeneratedOrdersResource resource,
            GeneratedJaxRsDescriptorSupport support,
            List<Annotation> classAnnotations,
            Method method,
            String httpMethod,
            String path,
            List<ParamMeta> params,
            List<String> consumes,
            @Nullable Class<?>[] validationGroups) {
        return new ResourceMethodMeta(
                resource,
                method,
                method.getName(),
                httpMethod,
                path,
                params,
                String.class,
                false,
                false,
                new SecurityPolicy.None(),
                new ResourceMethodMeta.MediaTypes(consumes, List.of(TEXT_PLAIN)),
                validationGroups,
                support.effectiveMethodAnnotations(method),
                classAnnotations,
                List.of(),
                List.of());
    }

    private static Method method(GeneratedJaxRsDescriptorSupport support, String name, String[] parameterTypes) {
        try {
            return support.resolveMethod(GeneratedOrdersResource.class, name, parameterTypes);
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            throw new IllegalStateException("fixture method GeneratedOrdersResource#" + name, e);
        }
    }

    private static Annotation[] annotations(String methodName, String[] parameterTypes, int index) {
        return GeneratedJaxRsReflectiveAnnotations.mergedParameterAnnotations(
                GeneratedOrdersResource_JaxRsDescriptor.class, RESOURCE_FQN, methodName, parameterTypes, index);
    }
}
