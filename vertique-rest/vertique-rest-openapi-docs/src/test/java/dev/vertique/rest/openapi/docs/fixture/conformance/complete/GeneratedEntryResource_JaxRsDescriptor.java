// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.conformance.complete;

import static dev.vertique.rest.jaxrs.ResourceMethodMeta.ParamSource.BEAN_PARAM;
import static dev.vertique.rest.jaxrs.ResourceMethodMeta.ParamSource.BODY;
import static dev.vertique.rest.jaxrs.ResourceMethodMeta.ParamSource.COOKIE;
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
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Hand-written companion in the generated descriptor shape for {@link GeneratedEntryResource} (the
 * annotation processor does not run on framework test sources): one literal {@link
 * ResourceMethodMeta} per operation, as the reflective scanner builds it for {@link
 * ReflectedEntryResource}. Each {@link ParamMeta} (name, source, type, component type, generic type,
 * default value) follows the method's parameters in declaration order, each with its merged
 * runtime-retained annotations supplied the way generated code supplies them; as in generated code,
 * only the request body carries a generic type. Each operation's id is its {@code @Operation} value,
 * its path joins the resource and method paths, its response body type is the return type with one
 * {@code Future} unwrapped, its media types are its method-level {@code @Consumes} and
 * {@code @Produces}, and its security policy is {@link SecurityPolicy.None}, since no operation
 * declares a role, scope, or permit annotation: its {@code @SecurityRequirement}s reach the runtime
 * through the method annotations. Found by the descriptor registry through the {@code
 * _JaxRsDescriptor} naming convention.
 */
public final class GeneratedEntryResource_JaxRsDescriptor
        implements GeneratedJaxRsResourceDescriptor<GeneratedEntryResource> {

    private static final String RESOURCE_FQN = GeneratedEntryResource.class.getName();

    private static final String STRING = "java.lang.String";

    private static final String GET_ENTRY_METHOD = "getEntry";

    private static final String CREATE_ENTRY_METHOD = "createEntry";

    private static final String ARCHIVE_ENTRY_METHOD = "archiveEntry";

    private static final String EXPORT_ENTRIES_METHOD = "exportEntries";

    private static final String LIVE_STATUS_METHOD = "liveStatus";

    private static final String[] GET_ENTRY_TYPES = {
        STRING, STRING, STRING, STRING, GeneratedPaging.class.getName(), GeneratedFilter.class.getName()
    };

    private static final String[] CREATE_ENTRY_TYPES = {EntryRequest.class.getName()};

    private static final String[] ARCHIVE_ENTRY_TYPES = {STRING};

    private static final String[] EXPORT_ENTRIES_TYPES = {STRING};

    private static final String[] LIVE_STATUS_TYPES = {};

    private static final String APPLICATION_JSON = "application/json";

    private static final String TEXT_PLAIN = "text/plain";

    /** No-arg constructor required for reflective instantiation by the registry. */
    public GeneratedEntryResource_JaxRsDescriptor() {}

    @Override
    public Class<GeneratedEntryResource> resourceType() {
        return GeneratedEntryResource.class;
    }

    @Override
    public List<ResourceMethodMeta> describe(
            GeneratedEntryResource resource,
            GeneratedJaxRsDescriptorSupport support,
            List<SecurityPolicyViolation> violations) {
        List<Annotation> classAnnotations = support.effectiveClassAnnotations(resourceType());

        List<ParamMeta> getEntryParams = List.of(
                new ParamMeta(
                        CompleteEntries.ENTRY_ID,
                        PATH,
                        String.class,
                        null,
                        null,
                        null,
                        annotations(GET_ENTRY_METHOD, GET_ENTRY_TYPES, 0)),
                new ParamMeta(
                        CompleteEntries.VIEW,
                        QUERY,
                        String.class,
                        null,
                        null,
                        CompleteEntries.VIEW_DEFAULT,
                        annotations(GET_ENTRY_METHOD, GET_ENTRY_TYPES, 1)),
                new ParamMeta(
                        CompleteEntries.TRACE_HEADER,
                        HEADER,
                        String.class,
                        null,
                        null,
                        null,
                        annotations(GET_ENTRY_METHOD, GET_ENTRY_TYPES, 2)),
                new ParamMeta(
                        CompleteEntries.SESSION_COOKIE,
                        COOKIE,
                        String.class,
                        null,
                        null,
                        null,
                        annotations(GET_ENTRY_METHOD, GET_ENTRY_TYPES, 3)),
                new ParamMeta(
                        null,
                        BEAN_PARAM,
                        GeneratedPaging.class,
                        null,
                        null,
                        null,
                        annotations(GET_ENTRY_METHOD, GET_ENTRY_TYPES, 4)),
                new ParamMeta(
                        null,
                        BEAN_PARAM,
                        GeneratedFilter.class,
                        null,
                        null,
                        null,
                        annotations(GET_ENTRY_METHOD, GET_ENTRY_TYPES, 5)));

        List<ParamMeta> createEntryParams = List.of(new ParamMeta(
                null,
                BODY,
                EntryRequest.class,
                null,
                EntryRequest.class,
                null,
                annotations(CREATE_ENTRY_METHOD, CREATE_ENTRY_TYPES, 0)));

        List<ParamMeta> archiveEntryParams = List.of(new ParamMeta(
                CompleteEntries.ENTRY_ID,
                PATH,
                String.class,
                null,
                null,
                null,
                annotations(ARCHIVE_ENTRY_METHOD, ARCHIVE_ENTRY_TYPES, 0)));

        List<ParamMeta> exportEntriesParams = List.of(new ParamMeta(
                CompleteEntries.FORMAT,
                QUERY,
                String.class,
                null,
                null,
                CompleteEntries.FORMAT_DEFAULT,
                annotations(EXPORT_ENTRIES_METHOD, EXPORT_ENTRIES_TYPES, 0)));

        return List.of(
                meta(
                        resource,
                        support,
                        classAnnotations,
                        method(support, GET_ENTRY_METHOD, GET_ENTRY_TYPES),
                        CompleteEntries.GET_ENTRY,
                        "GET",
                        CompleteEntries.ENTRY_ROUTE,
                        getEntryParams,
                        EntryView.class,
                        false,
                        new ResourceMethodMeta.MediaTypes(List.of(), List.of())),
                meta(
                        resource,
                        support,
                        classAnnotations,
                        method(support, CREATE_ENTRY_METHOD, CREATE_ENTRY_TYPES),
                        CompleteEntries.CREATE_ENTRY,
                        "POST",
                        CompleteEntries.RESOURCE_PATH,
                        createEntryParams,
                        EntryView.class,
                        false,
                        new ResourceMethodMeta.MediaTypes(List.of(APPLICATION_JSON), List.of(APPLICATION_JSON))),
                meta(
                        resource,
                        support,
                        classAnnotations,
                        method(support, ARCHIVE_ENTRY_METHOD, ARCHIVE_ENTRY_TYPES),
                        CompleteEntries.ARCHIVE_ENTRY,
                        "DELETE",
                        CompleteEntries.ENTRY_ROUTE,
                        archiveEntryParams,
                        ArchiveTicket.class,
                        false,
                        new ResourceMethodMeta.MediaTypes(List.of(), List.of())),
                meta(
                        resource,
                        support,
                        classAnnotations,
                        method(support, EXPORT_ENTRIES_METHOD, EXPORT_ENTRIES_TYPES),
                        CompleteEntries.EXPORT_ENTRIES,
                        "POST",
                        CompleteEntries.EXPORTS_ROUTE,
                        exportEntriesParams,
                        ExportTicket.class,
                        true,
                        new ResourceMethodMeta.MediaTypes(List.of(), List.of())),
                meta(
                        resource,
                        support,
                        classAnnotations,
                        method(support, LIVE_STATUS_METHOD, LIVE_STATUS_TYPES),
                        CompleteEntries.LIVE_STATUS,
                        "GET",
                        CompleteEntries.LIVE_ROUTE,
                        List.of(),
                        String.class,
                        false,
                        new ResourceMethodMeta.MediaTypes(List.of(), List.of(TEXT_PLAIN))));
    }

    private static ResourceMethodMeta meta(
            GeneratedEntryResource resource,
            GeneratedJaxRsDescriptorSupport support,
            List<Annotation> classAnnotations,
            Method method,
            String operationId,
            String httpMethod,
            String path,
            List<ParamMeta> params,
            Class<?> responseBodyType,
            boolean returnsFuture,
            ResourceMethodMeta.MediaTypes mediaTypes) {
        return new ResourceMethodMeta(
                resource,
                method,
                operationId,
                httpMethod,
                path,
                params,
                responseBodyType,
                returnsFuture,
                false,
                new SecurityPolicy.None(),
                mediaTypes,
                null,
                support.effectiveMethodAnnotations(method),
                classAnnotations,
                List.of(),
                List.of());
    }

    private static Method method(GeneratedJaxRsDescriptorSupport support, String name, String[] parameterTypes) {
        try {
            return support.resolveMethod(GeneratedEntryResource.class, name, parameterTypes);
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            throw new IllegalStateException("fixture method GeneratedEntryResource#" + name, e);
        }
    }

    private static Annotation[] annotations(String methodName, String[] parameterTypes, int index) {
        return GeneratedJaxRsReflectiveAnnotations.mergedParameterAnnotations(
                GeneratedEntryResource_JaxRsDescriptor.class, RESOURCE_FQN, methodName, parameterTypes, index);
    }
}
