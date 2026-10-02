// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.events;

import dev.vertique.rest.core.routing.RestOperationDescriptor;
import dev.vertique.rest.core.routing.SecurityRequirementSet;
import dev.vertique.rest.core.security.SecurityPolicy;
import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test-local {@link RestOperationDescriptor}. Only the identity fields carry values: no route here is
 * secured or negotiates content, so the security policy is {@link SecurityPolicy.None} and every
 * collection is empty. It stays test-local: {@code vertique-rest-test}, which ships
 * {@code TestOperationDescriptors}, depends on this module, so this module's tests cannot use that fixture.
 *
 * @param operationId   the operation identifier
 * @param httpMethod    the HTTP method
 * @param routeTemplate the route template
 */
record TestOperation(String operationId, String httpMethod, String routeTemplate) implements RestOperationDescriptor {

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
     * Hostile variant of {@link TestOperation}: it returns the same identity fields and empty members,
     * but its {@link #toString()}, {@link #equals(Object)} and {@link #hashCode()} each count their calls
     * and throw {@link IllegalStateException}. A test hands it to code that must never call those three,
     * then reads the named counters. The counters are per instance.
     */
    static final class Hostile implements RestOperationDescriptor {

        private final String operationId;
        private final String httpMethod;
        private final String routeTemplate;

        /** Calls of {@link #toString()}. */
        private final AtomicInteger toStringCalls = new AtomicInteger();

        /** Calls of {@link #equals(Object)}. */
        private final AtomicInteger equalsCalls = new AtomicInteger();

        /** Calls of {@link #hashCode()}. */
        private final AtomicInteger hashCodeCalls = new AtomicInteger();

        /**
         * Creates a hostile descriptor with the given identity.
         *
         * @param operationId   the operation identifier
         * @param httpMethod    the HTTP method
         * @param routeTemplate the route template
         */
        Hostile(String operationId, String httpMethod, String routeTemplate) {
            this.operationId = operationId;
            this.httpMethod = httpMethod;
            this.routeTemplate = routeTemplate;
        }

        /**
         * Returns how many times {@link #toString()} was called on this instance.
         *
         * @return the call count
         */
        int toStringCalls() {
            return toStringCalls.get();
        }

        /**
         * Returns how many times {@link #equals(Object)} was called on this instance.
         *
         * @return the call count
         */
        int equalsCalls() {
            return equalsCalls.get();
        }

        /**
         * Returns how many times {@link #hashCode()} was called on this instance.
         *
         * @return the call count
         */
        int hashCodeCalls() {
            return hashCodeCalls.get();
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
         * Counts the call and throws.
         *
         * @return never returns
         * @throws IllegalStateException always
         */
        @Override
        public String toString() {
            toStringCalls.incrementAndGet();
            throw new IllegalStateException("TestOperation.Hostile.toString() must not be called");
        }

        /**
         * Counts the call and throws.
         *
         * @param other ignored
         * @return never returns
         * @throws IllegalStateException always
         */
        @Override
        public boolean equals(Object other) {
            equalsCalls.incrementAndGet();
            throw new IllegalStateException("TestOperation.Hostile.equals(Object) must not be called");
        }

        /**
         * Counts the call and throws.
         *
         * @return never returns
         * @throws IllegalStateException always
         */
        @Override
        public int hashCode() {
            hashCodeCalls.incrementAndGet();
            throw new IllegalStateException("TestOperation.Hostile.hashCode() must not be called");
        }
    }
}
