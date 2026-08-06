// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.routing;

import dev.vertique.rest.core.routing.SecurityRequirementSet;
import dev.vertique.rest.core.security.SecurityPolicy;
import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Optional;

/**
 * Immutable {@link JaxRsOperationDescriptor} test double shared by the rest-jaxrs,
 * rest-validation, and rest-openapi-validation test suites.
 *
 * <p>{@link JaxRsOperationDescriptor} declares three own members plus ten inherited from
 * {@link dev.vertique.rest.core.routing.RestOperationDescriptor} and provides no defaults, so a
 * hand-rolled anonymous implementation costs roughly sixty lines even when the test under way cares
 * about two of them. This record makes the members that test call sites actually vary into
 * builder-settable components and fixes the remainder — {@link #produces()},
 * {@link #securityPolicy()}, {@link #securityRequirementSets()}, {@link #methodAnnotations()},
 * {@link #classAnnotations()}, and {@link #findAnnotation(Class)} — to the empty/none values every
 * hand-rolled stub returned.
 *
 * <p>Component values are exposed as supplied, without defensive copying, so a caller that keeps a
 * reference to the list or {@link Optional} it passed in sees the very same instance back.
 *
 * <p>This type is published in this module's {@code test-jar}. Downstream test modules consume it by
 * declaring {@code vertique-rest-jaxrs} with {@code <type>test-jar</type><scope>test</scope>}.
 *
 * <pre>{@code
 * JaxRsOperationDescriptor op = StubOperationDescriptor.builder()
 *         .operationId("createThing")
 *         .httpMethod("POST")
 *         .routeTemplate("/things")
 *         .body(Optional.of(new BodyDescriptor(Thing.class, null, List.of())))
 *         .build();
 * }</pre>
 *
 * @param operationId   the operation identifier
 * @param httpMethod    the HTTP method name, e.g. {@code "GET"}
 * @param routeTemplate the route template, e.g. {@code "/things/{id}"}
 * @param consumes      the declared {@code @Consumes} media types
 * @param parameters    the declared request parameters
 * @param fileParts     the declared file-part constraints
 * @param body          the declared request body descriptor, or {@link Optional#empty()} when the
 *                      operation declares no body
 */
public record StubOperationDescriptor(
        String operationId,
        String httpMethod,
        String routeTemplate,
        List<String> consumes,
        List<ParamDescriptor> parameters,
        List<FilePartDescriptor> fileParts,
        Optional<BodyDescriptor> body)
        implements JaxRsOperationDescriptor {

    /** The policy every stub reports: no authorization annotations present on the operation. */
    private static final SecurityPolicy NO_SECURITY = new SecurityPolicy.None();

    // --- Fixed members ---

    /**
     * Returns no produced media types.
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
     * Returns no security requirement sets, i.e. a public operation.
     *
     * @return the empty list
     */
    @Override
    public List<SecurityRequirementSet> securityRequirementSets() {
        return List.of();
    }

    /**
     * Returns no method-level annotations.
     *
     * @return the empty list
     */
    @Override
    public List<Annotation> methodAnnotations() {
        return List.of();
    }

    /**
     * Returns no class-level annotations.
     *
     * @return the empty list
     */
    @Override
    public List<Annotation> classAnnotations() {
        return List.of();
    }

    /**
     * Finds no annotation, matching the empty {@link #methodAnnotations()} and
     * {@link #classAnnotations()} views.
     *
     * @param type the annotation type to look up
     * @param <A>  the annotation type
     * @return {@link Optional#empty()} always
     */
    @Override
    public <A extends Annotation> Optional<A> findAnnotation(Class<A> type) {
        return Optional.empty();
    }

    // --- Construction ---

    /**
     * Creates a builder carrying neutral defaults: operation {@code "op"}, method {@code "GET"},
     * route {@code "/"}, and no consumed media types, parameters, file parts, or body.
     *
     * @return a fresh builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Mutable builder for {@link StubOperationDescriptor}. Every setter is optional; unset members
     * keep the defaults documented on {@link StubOperationDescriptor#builder()}.
     */
    public static final class Builder {

        private String operationId = "op";
        private String httpMethod = "GET";
        private String routeTemplate = "/";
        private List<String> consumes = List.of();
        private List<ParamDescriptor> parameters = List.of();
        private List<FilePartDescriptor> fileParts = List.of();
        private Optional<BodyDescriptor> body = Optional.empty();

        private Builder() {}

        /**
         * Sets the operation identifier.
         *
         * @param operationId the operation identifier
         * @return this builder
         */
        public Builder operationId(String operationId) {
            this.operationId = operationId;
            return this;
        }

        /**
         * Sets the HTTP method name.
         *
         * @param httpMethod the HTTP method name, e.g. {@code "POST"}
         * @return this builder
         */
        public Builder httpMethod(String httpMethod) {
            this.httpMethod = httpMethod;
            return this;
        }

        /**
         * Sets the route template.
         *
         * @param routeTemplate the route template, e.g. {@code "/things/{id}"}
         * @return this builder
         */
        public Builder routeTemplate(String routeTemplate) {
            this.routeTemplate = routeTemplate;
            return this;
        }

        /**
         * Sets the declared {@code @Consumes} media types.
         *
         * @param consumes the consumed media types
         * @return this builder
         */
        public Builder consumes(List<String> consumes) {
            this.consumes = consumes;
            return this;
        }

        /**
         * Sets the declared request parameters.
         *
         * @param parameters the parameter descriptors
         * @return this builder
         */
        public Builder parameters(List<ParamDescriptor> parameters) {
            this.parameters = parameters;
            return this;
        }

        /**
         * Sets the declared file-part constraints.
         *
         * @param fileParts the file-part descriptors
         * @return this builder
         */
        public Builder fileParts(List<FilePartDescriptor> fileParts) {
            this.fileParts = fileParts;
            return this;
        }

        /**
         * Sets the declared request body descriptor.
         *
         * @param body the body descriptor, or {@link Optional#empty()} for a body-less operation
         * @return this builder
         */
        public Builder body(Optional<BodyDescriptor> body) {
            this.body = body;
            return this;
        }

        /**
         * Builds the descriptor.
         *
         * @return a new {@link StubOperationDescriptor} carrying the configured members
         */
        public StubOperationDescriptor build() {
            return new StubOperationDescriptor(
                    operationId, httpMethod, routeTemplate, consumes, parameters, fileParts, body);
        }
    }
}
