// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation;

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
 * Builds synthetic {@link JaxRsOperationDescriptor} instances for unit tests.
 *
 * <p>Test doubles only. A test needing a descriptor produced by the real annotation scanner should
 * mount resources through the fixture rather than build one here.
 *
 * <p>Reusable, not thread-safe. Each {@link Builder#build()} returns an independent immutable
 * descriptor, so mutating the builder afterwards does not affect an already-built instance.
 *
 * <p>{@code securityPolicy} and {@code securityRequirementSets} are fixed at {@code None} / empty
 * and have no setter: no test varies them today. Add one when a test needs security-sensitive
 * descriptor data.
 */
final class StubDescriptors {

    /** Not instantiable. */
    private StubDescriptors() {}

    /**
     * Returns a builder pre-loaded with neutral defaults: operation id {@code "op"}, method
     * {@code "GET"}, route {@code "/"}, empty {@code consumes}/{@code parameters}/{@code fileParts},
     * and no body.
     *
     * @return a new builder; never {@code null}
     */
    static Builder builder() {
        return new Builder();
    }

    /**
     * Fluent builder for a synthetic descriptor. Every setter rejects {@code null}.
     *
     * <p>The constructor is private: instances come from {@link StubDescriptors#builder()} only.
     * Without it Java would emit a default constructor and the facade could be bypassed.
     */
    static final class Builder {

        private String operationId = "op";
        private String httpMethod = "GET";
        private String routeTemplate = "/";
        private List<String> consumes = List.of();
        private List<ParamDescriptor> parameters = List.of();
        private List<FilePartDescriptor> fileParts = List.of();
        private Optional<BodyDescriptor> body = Optional.empty();

        /** Use {@link StubDescriptors#builder()}. */
        private Builder() {}

        /**
         * Sets the operation identifier.
         *
         * @param operationId the operation identifier the built descriptor reports
         * @return this builder
         * @throws NullPointerException if {@code operationId} is {@code null}
         */
        Builder operationId(String operationId) {
            this.operationId = Objects.requireNonNull(operationId, "operationId");
            return this;
        }

        /**
         * Sets the HTTP method.
         *
         * @param httpMethod the HTTP method name the built descriptor reports, e.g. {@code "POST"}
         * @return this builder
         * @throws NullPointerException if {@code httpMethod} is {@code null}
         */
        Builder httpMethod(String httpMethod) {
            this.httpMethod = Objects.requireNonNull(httpMethod, "httpMethod");
            return this;
        }

        /**
         * Sets the route template.
         *
         * @param routeTemplate the route template the built descriptor reports, e.g.
         *                      {@code "/users/{id}"}
         * @return this builder
         * @throws NullPointerException if {@code routeTemplate} is {@code null}
         */
        Builder routeTemplate(String routeTemplate) {
            this.routeTemplate = Objects.requireNonNull(routeTemplate, "routeTemplate");
            return this;
        }

        /**
         * Sets the declared {@code @Consumes} media types. The list is copied here, in the setter, so
         * mutating {@code consumes} afterwards affects neither this builder nor any descriptor built
         * from it — whether built before or after the mutation.
         *
         * @param consumes the consumed media types; must contain no {@code null} element
         * @return this builder
         * @throws NullPointerException if {@code consumes} is {@code null} or contains {@code null}
         */
        Builder consumes(List<String> consumes) {
            this.consumes = List.copyOf(Objects.requireNonNull(consumes, "consumes"));
            return this;
        }

        /**
         * Sets the declared request parameters. The list is copied here, in the setter, so mutating
         * {@code parameters} afterwards affects neither this builder nor any descriptor built from it
         * — whether built before or after the mutation.
         *
         * @param parameters the parameter descriptors; must contain no {@code null} element
         * @return this builder
         * @throws NullPointerException if {@code parameters} is {@code null} or contains {@code null}
         */
        Builder parameters(List<ParamDescriptor> parameters) {
            this.parameters = List.copyOf(Objects.requireNonNull(parameters, "parameters"));
            return this;
        }

        /**
         * Sets the file-part constraint metadata. The list is copied here, in the setter, so mutating
         * {@code fileParts} afterwards affects neither this builder nor any descriptor built from it
         * — whether built before or after the mutation.
         *
         * @param fileParts the file-part descriptors; must contain no {@code null} element
         * @return this builder
         * @throws NullPointerException if {@code fileParts} is {@code null} or contains {@code null}
         */
        Builder fileParts(List<FilePartDescriptor> fileParts) {
            this.fileParts = List.copyOf(Objects.requireNonNull(fileParts, "fileParts"));
            return this;
        }

        /**
         * Sets the declared request body. A builder on which this is never called builds a descriptor
         * whose {@code body()} is {@link Optional#empty()}.
         *
         * @param body the body descriptor the built descriptor reports
         * @return this builder
         * @throws NullPointerException if {@code body} is {@code null}
         */
        Builder body(BodyDescriptor body) {
            this.body = Optional.of(Objects.requireNonNull(body, "body"));
            return this;
        }

        /**
         * Builds an immutable descriptor from the builder's current state.
         *
         * @return a descriptor independent of this builder and of every other descriptor it built
         */
        JaxRsOperationDescriptor build() {
            return new StubDescriptor(operationId, httpMethod, routeTemplate, consumes, parameters, fileParts, body);
        }
    }

    /**
     * Immutable descriptor produced by {@link Builder#build()}. The six members without a setter are
     * fixed at their neutral values.
     *
     * @param operationId   the operation identifier
     * @param httpMethod    the HTTP method name
     * @param routeTemplate the route template
     * @param consumes      the consumed media types, already copied by the setter
     * @param parameters    the parameter descriptors, already copied by the setter
     * @param fileParts     the file-part descriptors, already copied by the setter
     * @param body          the request body descriptor, or {@link Optional#empty()} when none
     */
    private record StubDescriptor(
            String operationId,
            String httpMethod,
            String routeTemplate,
            List<String> consumes,
            List<ParamDescriptor> parameters,
            List<FilePartDescriptor> fileParts,
            Optional<BodyDescriptor> body)
            implements JaxRsOperationDescriptor {

        @Override
        public List<String> produces() {
            return List.of();
        }

        @Override
        public SecurityPolicy securityPolicy() {
            return new SecurityPolicy.None();
        }

        @Override
        public List<SecurityRequirementSet> securityRequirementSets() {
            return List.of();
        }

        @Override
        public List<Annotation> methodAnnotations() {
            return List.of();
        }

        @Override
        public List<Annotation> classAnnotations() {
            return List.of();
        }

        @Override
        public <A extends Annotation> Optional<A> findAnnotation(Class<A> type) {
            return Optional.empty();
        }
    }
}
