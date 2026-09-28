// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.test;

import dev.vertique.rest.core.routing.RestOperationDescriptor;
import dev.vertique.rest.core.routing.SecurityRequirementSet;
import dev.vertique.rest.core.security.SecurityPolicy;
import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Builds {@link RestOperationDescriptor}s for unit tests of completion listeners and other
 * descriptor consumers, for example to build a
 * {@link dev.vertique.rest.core.events.RestRequestCompletedEvent} in a listener test.
 *
 * <p>A descriptor from {@link #of(String, String, String)} carries only its operation identity. Its
 * media-type, security-requirement and annotation lists are empty, its security policy is
 * {@link SecurityPolicy.None} (no security annotations), and {@code findAnnotation} finds nothing.
 *
 * <p>Compare descriptors by identity, never by value. Like the descriptors the framework builds for
 * operation routes, a descriptor from this class compares by identity: each call returns a new
 * instance, equal only to itself, and no value-equality promise is made. Its {@code toString} is the
 * same compact {@code <httpMethod> <routeTemplate> (<operationId>)} form, for example
 * {@code GET /users/{id} (getUser)}, which is not a parse format.
 */
public final class TestOperationDescriptors {

    private TestOperationDescriptors() {}

    /**
     * Returns a new descriptor carrying only the given operation identity.
     *
     * @param httpMethod    the HTTP method name, e.g. {@code "GET"}; never {@code null}
     * @param routeTemplate the route template, e.g. {@code "/users/{id}"}; never {@code null}
     * @param operationId   the operation identifier, e.g. {@code "getUser"}; never {@code null}
     * @return a new descriptor, equal only to itself
     * @throws NullPointerException if {@code httpMethod}, {@code routeTemplate} or
     *                              {@code operationId} is {@code null}
     */
    public static RestOperationDescriptor of(String httpMethod, String routeTemplate, String operationId) {
        Objects.requireNonNull(httpMethod, "httpMethod");
        Objects.requireNonNull(routeTemplate, "routeTemplate");
        Objects.requireNonNull(operationId, "operationId");
        return new IdentityDescriptor(httpMethod, routeTemplate, operationId);
    }

    /**
     * Identity-only {@link RestOperationDescriptor}: the three identity values, empty members, and
     * {@link SecurityPolicy.None}. It keeps {@link Object}'s identity {@code equals} and
     * {@code hashCode}, and renders compactly.
     */
    private static final class IdentityDescriptor implements RestOperationDescriptor {

        private final String httpMethod;
        private final String routeTemplate;
        private final String operationId;

        /**
         * Creates the descriptor.
         *
         * @param httpMethod    the HTTP method name
         * @param routeTemplate the route template
         * @param operationId   the operation identifier
         */
        IdentityDescriptor(String httpMethod, String routeTemplate, String operationId) {
            this.httpMethod = httpMethod;
            this.routeTemplate = routeTemplate;
            this.operationId = operationId;
        }

        @Override
        public String operationId() {
            return operationId;
        }

        @Override
        public String httpMethod() {
            return httpMethod;
        }

        @Override
        public String routeTemplate() {
            return routeTemplate;
        }

        @Override
        public List<String> consumes() {
            return List.of();
        }

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

        /**
         * Returns the compact {@code <httpMethod> <routeTemplate> (<operationId>)} rendering, for
         * example {@code GET /users/{id} (getUser)}.
         *
         * @return the compact rendering of this descriptor
         */
        @Override
        public String toString() {
            return httpMethod + " " + routeTemplate + " (" + operationId + ")";
        }
    }
}
