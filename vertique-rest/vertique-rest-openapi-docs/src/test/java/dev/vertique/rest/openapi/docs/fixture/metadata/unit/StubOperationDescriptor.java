// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.metadata.unit;

import dev.vertique.rest.core.routing.SecurityRequirementSet;
import dev.vertique.rest.core.security.SecurityPolicy;
import dev.vertique.rest.jaxrs.routing.BodyDescriptor;
import dev.vertique.rest.jaxrs.routing.FilePartDescriptor;
import dev.vertique.rest.jaxrs.routing.JaxRsOperationDescriptor;
import dev.vertique.rest.jaxrs.routing.ParamDescriptor;
import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * An immutable operation descriptor for synthetic publications, carrying the facts the documentation
 * sink reads from a real descriptor before it detaches the publication: the consumed media types,
 * the parameters (each with its element type), the named file parts, and the resolved method and
 * class annotations.
 *
 * <p>The annotations are the ones the caller passes, normally real instances resolved from a fixture
 * method and its class (see {@link MetadataPublications}). {@link #findAnnotation} searches the
 * method annotations first, then the class annotations, as the runtime descriptors do. Every other
 * member is fixed: nothing produced, no security policy or requirement set.
 *
 * @param operationId the operation id
 * @param httpMethod the HTTP method, for example {@code "GET"}
 * @param routeTemplate the declared path template
 * @param consumes the consumed media types, in declaration order; empty when none is declared
 * @param parameters the bound method parameters, in declaration order
 * @param fileParts the declared file parts
 * @param body the body descriptor, or empty when the operation binds no body
 * @param methodAnnotations the resolved method annotations, in resolution order
 * @param classAnnotations the resolved class annotations, in resolution order
 */
public record StubOperationDescriptor(
        String operationId,
        String httpMethod,
        String routeTemplate,
        List<String> consumes,
        List<ParamDescriptor> parameters,
        List<FilePartDescriptor> fileParts,
        Optional<BodyDescriptor> body,
        List<Annotation> methodAnnotations,
        List<Annotation> classAnnotations)
        implements JaxRsOperationDescriptor {

    private static final SecurityPolicy NO_SECURITY = new SecurityPolicy.None();

    /** Copies every list unmodifiably; {@code null} components are rejected. */
    public StubOperationDescriptor {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(httpMethod, "httpMethod");
        Objects.requireNonNull(routeTemplate, "routeTemplate");
        consumes = List.copyOf(consumes);
        parameters = List.copyOf(parameters);
        fileParts = List.copyOf(fileParts);
        Objects.requireNonNull(body, "body");
        methodAnnotations = List.copyOf(methodAnnotations);
        classAnnotations = List.copyOf(classAnnotations);
    }

    /**
     * Returns no produced media type.
     *
     * @return the empty list
     */
    @Override
    public List<String> produces() {
        return List.of();
    }

    /**
     * Returns the unsecured policy.
     *
     * @return a {@link SecurityPolicy.None}
     */
    @Override
    public SecurityPolicy securityPolicy() {
        return NO_SECURITY;
    }

    /**
     * Returns no security requirement set.
     *
     * @return the empty list
     */
    @Override
    public List<SecurityRequirementSet> securityRequirementSets() {
        return List.of();
    }

    /**
     * Finds the first annotation of a type among the method annotations, then the class annotations.
     *
     * @param type the annotation type
     * @param <A> the annotation type
     * @return the annotation, or empty when neither list holds one
     */
    @Override
    public <A extends Annotation> Optional<A> findAnnotation(Class<A> type) {
        for (Annotation annotation : methodAnnotations) {
            if (type.isInstance(annotation)) {
                return Optional.of(type.cast(annotation));
            }
        }
        for (Annotation annotation : classAnnotations) {
            if (type.isInstance(annotation)) {
                return Optional.of(type.cast(annotation));
            }
        }
        return Optional.empty();
    }
}
