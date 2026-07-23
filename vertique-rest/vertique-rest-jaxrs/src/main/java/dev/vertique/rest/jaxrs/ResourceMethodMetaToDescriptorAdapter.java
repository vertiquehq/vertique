// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import dev.vertique.rest.core.request.FilePart;
import dev.vertique.rest.core.routing.SecurityRequirementSet;
import dev.vertique.rest.core.security.SecurityPolicy;
import dev.vertique.rest.jaxrs.routing.BodyDescriptor;
import dev.vertique.rest.jaxrs.routing.FilePartDescriptor;
import dev.vertique.rest.jaxrs.routing.JaxRsOperationDescriptor;
import dev.vertique.rest.jaxrs.routing.ParamDescriptor;
import dev.vertique.rest.jaxrs.routing.ParamLocation;
import io.swagger.v3.oas.annotations.Operation;
import io.vertx.ext.web.FileUpload;
import jakarta.ws.rs.core.EntityPart;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Maps the internal {@link ResourceMethodMeta} to the public {@link JaxRsOperationDescriptor} view
 * consumed by the rest-jaxrs validation and schema-synthesis seams.
 *
 * <p>The adapter resolves the operationId from {@code @Operation(operationId)} when present and
 * non-blank, otherwise the Java method name, and projects the meta's parameter model into
 * {@link ParamDescriptor}s (path/query/header/cookie/form) and {@link BodyDescriptor} (the body
 * parameter, if any), and an additional file-part validation view.
 */
final class ResourceMethodMetaToDescriptorAdapter {

    private ResourceMethodMetaToDescriptorAdapter() {}

    /**
     * Adapts the given {@link ResourceMethodMeta} into a {@link JaxRsOperationDescriptor}.
     *
     * @param meta the internal resource-method metadata; must not be {@code null}
     * @return a descriptor exposing the operation's identity, security, annotations, parameters, and
     *     body
     */
    static JaxRsOperationDescriptor adapt(ResourceMethodMeta meta) {
        String operationId = resolveOperationId(meta);
        List<ParamDescriptor> parameters = mapParameters(meta.params());
        List<FilePartDescriptor> fileParts = mapFileParts(meta.params());
        Optional<BodyDescriptor> body = mapBody(meta.params());
        // The scanner returns the OR-of-AND model directly: each @SecurityRequirement is one set —
        // single-scheme for a name() requirement, multi-scheme (AND) for a combine() requirement.
        // The adapter passes the sets through unchanged. A malformed @SecurityRequirement (neither or
        // both of name/combine) fails startup inside the scanner rather than being silently dropped.
        List<SecurityRequirementSet> securityRequirementSets = SecuritySchemeAnnotationScanner.effectiveRequirements(
                meta.methodAnnotations(), meta.classAnnotations());
        return new AdaptedDescriptor(meta, operationId, parameters, fileParts, body, securityRequirementSets);
    }

    /**
     * Resolves the operationId: the {@code @Operation(operationId)} value when present and
     * non-blank (looked up method-first then class), otherwise the Java method name.
     *
     * @param meta the resource-method metadata
     * @return the resolved operationId
     */
    private static String resolveOperationId(ResourceMethodMeta meta) {
        Operation operation = findAnnotation(meta, Operation.class);
        if (operation != null && !operation.operationId().isBlank()) {
            return operation.operationId();
        }
        return meta.method().getName();
    }

    /**
     * Projects the bindable parameters (path, query, header, cookie, form) into
     * {@link ParamDescriptor}s, skipping body, context, and other non-bindable sources.
     *
     * @param params the meta's parameter model
     * @return the ordered list of parameter descriptors
     */
    private static List<ParamDescriptor> mapParameters(List<ResourceMethodMeta.ParamMeta> params) {
        List<ParamDescriptor> result = new ArrayList<>();
        for (ResourceMethodMeta.ParamMeta pm : params) {
            if (isBindableParam(pm.source())) {
                result.add(new ParamDescriptor(
                        pm.name(),
                        toLocation(pm.source()),
                        pm.type(),
                        pm.componentType(),
                        pm.genericType(),
                        pm.defaultValue(),
                        List.of(pm.annotationsLazy().get())));
            }
        }
        return List.copyOf(result);
    }

    /**
     * Projects every file-bearing parameter into the additional validation view. Constraints are
     * read only from {@link FilePart} on Vert.x {@link FileUpload} parameters; JAX-RS {@link
     * EntityPart} parameters remain unconstrained because they may represent text form fields.
     *
     * @param params the meta's parameter model
     * @return the ordered list of file-part descriptors
     */
    private static List<FilePartDescriptor> mapFileParts(List<ResourceMethodMeta.ParamMeta> params) {
        List<FilePartDescriptor> result = new ArrayList<>();
        for (ResourceMethodMeta.ParamMeta pm : params) {
            boolean fileUpload = pm.type() == FileUpload.class || pm.componentType() == FileUpload.class;
            boolean entityPart = pm.type() == EntityPart.class || pm.componentType() == EntityPart.class;
            if (!fileUpload && !entityPart) {
                continue;
            }

            String partName =
                    switch (pm.source()) {
                        case FILE_UPLOADS, ENTITY_PARTS -> null;
                        default -> pm.name();
                    };
            FilePart constraint = fileUpload ? pm.findAnnotation(FilePart.class).orElse(null) : null;
            result.add(
                    constraint == null
                            ? new FilePartDescriptor(partName, List.of(), -1)
                            : new FilePartDescriptor(
                                    partName, List.of(constraint.allowedTypes()), constraint.maxSizeBytes()));
        }
        return List.copyOf(result);
    }

    /**
     * Finds the body parameter (if any) and maps it to a {@link BodyDescriptor}.
     *
     * @param params the meta's parameter model
     * @return the body descriptor, or {@link Optional#empty()} when no body parameter is declared
     */
    private static Optional<BodyDescriptor> mapBody(List<ResourceMethodMeta.ParamMeta> params) {
        for (ResourceMethodMeta.ParamMeta pm : params) {
            if (pm.source() == ResourceMethodMeta.ParamSource.BODY) {
                return Optional.of(new BodyDescriptor(
                        pm.type(),
                        pm.genericType(),
                        List.of(pm.annotationsLazy().get())));
            }
        }
        return Optional.empty();
    }

    /**
     * Returns whether the parameter source is bindable into a {@link ParamDescriptor} (path, query,
     * header, cookie, or form).
     *
     * @param source the parameter source
     * @return {@code true} for the bindable request-parameter sources
     */
    private static boolean isBindableParam(ResourceMethodMeta.ParamSource source) {
        return switch (source) {
            case PATH, QUERY, HEADER, COOKIE, FORM -> true;
            default -> false;
        };
    }

    /**
     * Maps an internal bindable {@link ResourceMethodMeta.ParamSource} to its public
     * {@link ParamLocation}. Only the five request-bindable sources are expected here; callers must
     * gate on {@link #isBindableParam(ResourceMethodMeta.ParamSource)} first.
     *
     * @param source the bindable parameter source
     * @return the corresponding public {@link ParamLocation}
     * @throws IllegalStateException if {@code source} is not one of the five bindable locations,
     *     which {@link #isBindableParam(ResourceMethodMeta.ParamSource)} guarantees never happens
     */
    private static ParamLocation toLocation(ResourceMethodMeta.ParamSource source) {
        return switch (source) {
            case PATH -> ParamLocation.PATH;
            case QUERY -> ParamLocation.QUERY;
            case HEADER -> ParamLocation.HEADER;
            case COOKIE -> ParamLocation.COOKIE;
            case FORM -> ParamLocation.FORM;
            default -> throw new IllegalStateException("Non-bindable parameter source: " + source);
        };
    }

    /**
     * Finds an annotation of the given type, searching the meta's method annotations first then its
     * class annotations.
     *
     * @param meta the resource-method metadata
     * @param type the annotation type to find
     * @param <A>  the annotation type
     * @return the annotation, or {@code null} when absent on both method and class
     */
    private static <A extends Annotation> A findAnnotation(ResourceMethodMeta meta, Class<A> type) {
        for (Annotation annotation : meta.methodAnnotations()) {
            if (type.isInstance(annotation)) {
                return type.cast(annotation);
            }
        }
        for (Annotation annotation : meta.classAnnotations()) {
            if (type.isInstance(annotation)) {
                return type.cast(annotation);
            }
        }
        return null;
    }

    /**
     * {@link JaxRsOperationDescriptor} backed by a {@link ResourceMethodMeta} plus the pre-resolved
     * operationId, parameters, file parts, body, and effective security requirement sets.
     *
     * @param meta                    the backing resource-method metadata
     * @param operationId             the resolved operationId
     * @param parameters              the projected parameter descriptors
     * @param fileParts               the projected file-part validation descriptors
     * @param body                    the projected body descriptor, if any
     * @param securityRequirementSets the effective annotation-sourced security requirement sets (the
     *                                OR alternatives; each set is single-scheme for a {@code name()}
     *                                requirement or multi-scheme for a {@code combine()} requirement)
     */
    private record AdaptedDescriptor(
            ResourceMethodMeta meta,
            String operationId,
            List<ParamDescriptor> parameters,
            List<FilePartDescriptor> fileParts,
            Optional<BodyDescriptor> body,
            List<SecurityRequirementSet> securityRequirementSets)
            implements JaxRsOperationDescriptor {

        @Override
        public String operationId() {
            return operationId;
        }

        @Override
        public String httpMethod() {
            return meta.httpMethod();
        }

        @Override
        public String routeTemplate() {
            return meta.path();
        }

        @Override
        public List<String> consumes() {
            return meta.mediaTypes().consumes();
        }

        @Override
        public List<String> produces() {
            return meta.mediaTypes().produces();
        }

        @Override
        public SecurityPolicy securityPolicy() {
            return meta.securityPolicy();
        }

        @Override
        public List<Annotation> methodAnnotations() {
            return meta.methodAnnotations();
        }

        @Override
        public List<Annotation> classAnnotations() {
            return meta.classAnnotations();
        }

        @Override
        public <A extends Annotation> Optional<A> findAnnotation(Class<A> type) {
            return Optional.ofNullable(ResourceMethodMetaToDescriptorAdapter.findAnnotation(meta, type));
        }

        @Override
        public List<ParamDescriptor> parameters() {
            return parameters;
        }

        @Override
        public List<FilePartDescriptor> fileParts() {
            return fileParts;
        }

        @Override
        public Optional<BodyDescriptor> body() {
            return body;
        }
    }
}
