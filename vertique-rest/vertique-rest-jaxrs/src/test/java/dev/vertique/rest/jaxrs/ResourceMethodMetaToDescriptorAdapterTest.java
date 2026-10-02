// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.rest.core.events.RestRequestCompletedEvent;
import dev.vertique.rest.core.routing.RestOperationDescriptor;
import dev.vertique.rest.core.routing.SecurityRequirement;
import dev.vertique.rest.core.routing.SecurityRequirementSet;
import dev.vertique.rest.core.security.SecurityPolicy;
import dev.vertique.rest.jaxrs.routing.JaxRsOperationDescriptor;
import io.swagger.v3.oas.annotations.Operation;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ResourceMethodMetaToDescriptorAdapter}: the mapping from the internal
 * {@link ResourceMethodMeta} to the public {@link JaxRsOperationDescriptor}, with focus on
 * operationId resolution, and the descriptor's own {@code toString}, {@code equals} and
 * {@code hashCode}, which must never reach the application's resource instance (T003 TP-010).
 */
class ResourceMethodMetaToDescriptorAdapterTest {

    /** TP-010's expected compact rendering of the {@link HostileResource} descriptor. */
    private static final String HOSTILE_RENDERING = "GET /users/{id} (getUser)";

    /** TP-010's expected rendering of the operation inside a completion event's {@code toString}. */
    private static final String EVENT_OPERATION_RENDERING = "getUser /users/{id}";

    /** Fixed event instants, so two events built from the same descriptor are equal. */
    private static final Instant EVENT_START = Instant.parse("2026-01-01T00:00:00Z");

    private static final Instant EVENT_END = Instant.parse("2026-01-01T00:00:01Z");

    /** TP-010's hostile resource, created fresh (counters at zero) before every test. */
    private HostileResource hostileResource;

    @BeforeEach
    void resetHostileResource() {
        hostileResource = new HostileResource();
    }

    /** Fixture resource carrying methods annotated for operationId resolution. */
    static class FixtureResource {
        @Operation(operationId = "getUser")
        public String annotatedMethod() {
            return "x";
        }

        public String plainMethod() {
            return "y";
        }

        /**
         * Carries two OR alternatives via {@code @Operation.security}, so the scanner produces a flat
         * two-element requirement list the adapter must wrap into two single-scheme sets in order.
         */
        @Operation(
                operationId = "orSecured",
                security = {
                    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth"),
                    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
                            name = "apiKey",
                            scopes = {"read"})
                })
        public String orSecuredMethod() {
            return "z";
        }
    }

    /** Resolves the method annotations declared on a fixture method into an annotation list. */
    private static List<Annotation> annotationsOf(String methodName) throws NoSuchMethodException {
        Method method = FixtureResource.class.getMethod(methodName);
        return Arrays.asList(method.getAnnotations());
    }

    /**
     * TP-010's fixture resource: one {@code @Operation(operationId = "getUser")} method, and a
     * {@link #toString()}, {@link #equals(Object)} and {@link #hashCode()} that each count their calls
     * and throw {@link IllegalStateException}. Anything that logs, compares or hashes a descriptor or
     * event built on it and reaches this instance therefore both throws and leaves a nonzero counter.
     */
    static final class HostileResource {

        /** Calls of {@link #toString()}. */
        final AtomicInteger toStringCalls = new AtomicInteger();

        /** Calls of {@link #equals(Object)}. */
        final AtomicInteger equalsCalls = new AtomicInteger();

        /** Calls of {@link #hashCode()}. */
        final AtomicInteger hashCodeCalls = new AtomicInteger();

        /**
         * The operation the adapted descriptor describes.
         *
         * @return a constant body
         */
        @Operation(operationId = "getUser")
        public String getUser() {
            return "user";
        }

        @Override
        public String toString() {
            toStringCalls.incrementAndGet();
            throw new IllegalStateException("HostileResource.toString() must never be called");
        }

        @Override
        public boolean equals(Object other) {
            equalsCalls.incrementAndGet();
            throw new IllegalStateException("HostileResource.equals(Object) must never be called");
        }

        @Override
        public int hashCode() {
            hashCodeCalls.incrementAndGet();
            throw new IllegalStateException("HostileResource.hashCode() must never be called");
        }
    }

    /** Builds a {@link ResourceMethodMeta} for a fixture method with the supplied method annotations. */
    private static ResourceMethodMeta metaFor(
            String methodName, String operationId, String httpMethod, String path, List<Annotation> methodAnnotations)
            throws NoSuchMethodException {
        return metaFor(
                new FixtureResource(),
                FixtureResource.class.getMethod(methodName),
                operationId,
                httpMethod,
                path,
                methodAnnotations);
    }

    /**
     * Builds TP-010's meta: {@link HostileResource#getUser()} as {@code GET /users/{id}}, operation id
     * {@code getUser}, with the method's own annotations, on the given hostile instance.
     *
     * @param resource the hostile resource instance the meta carries
     * @return the meta
     * @throws NoSuchMethodException never; the fixture declares the method
     */
    private static ResourceMethodMeta hostileMeta(HostileResource resource) throws NoSuchMethodException {
        Method method = HostileResource.class.getMethod("getUser");
        return metaFor(resource, method, "getUser", "GET", "/users/{id}", Arrays.asList(method.getAnnotations()));
    }

    /** Builds a {@link ResourceMethodMeta} for {@code method} on {@code resource}, with the supplied annotations. */
    private static ResourceMethodMeta metaFor(
            Object resource,
            Method method,
            String operationId,
            String httpMethod,
            String path,
            List<Annotation> methodAnnotations) {
        return new ResourceMethodMeta(
                resource,
                method,
                operationId,
                httpMethod,
                path,
                List.of(),
                method.getReturnType(),
                false,
                false,
                new SecurityPolicy.None(),
                ResourceMethodMeta.MediaTypes.EMPTY,
                null,
                methodAnnotations,
                List.of(),
                List.of(),
                List.of());
    }

    @Test
    @DisplayName("Adapter maps operationId from @Operation, plus httpMethod and routeTemplate")
    void resourceMethodMetaAdapterMapsOperationId() throws NoSuchMethodException {
        ResourceMethodMeta meta =
                metaFor("annotatedMethod", "getUser", "GET", "/users/{id}", annotationsOf("annotatedMethod"));

        JaxRsOperationDescriptor op = ResourceMethodMetaToDescriptorAdapter.adapt(meta);

        assertEquals("getUser", op.operationId());
        assertEquals("GET", op.httpMethod());
        assertEquals("/users/{id}", op.routeTemplate());
    }

    @Test
    @DisplayName("Adapter falls back to the Java method name when no @Operation is present")
    void resourceMethodMetaAdapterFallsBackToMethodName() throws NoSuchMethodException {
        ResourceMethodMeta meta =
                metaFor("plainMethod", "plainMethod", "POST", "/things", annotationsOf("plainMethod"));

        JaxRsOperationDescriptor op = ResourceMethodMetaToDescriptorAdapter.adapt(meta);

        assertEquals("plainMethod", op.operationId());
    }

    @Test
    @DisplayName("Adapter wraps a flat N-requirement OR list into N single-scheme sets, in order (behavior-preserving)")
    void resourceMethodMetaAdapterWrapsRequirementsIntoSingleSchemeSets() throws NoSuchMethodException {
        ResourceMethodMeta meta =
                metaFor("orSecuredMethod", "orSecured", "GET", "/or-secured", annotationsOf("orSecuredMethod"));

        JaxRsOperationDescriptor op = ResourceMethodMetaToDescriptorAdapter.adapt(meta);

        // The scanner yields a flat OR list [bearerAuth, apiKey(read)]; the adapter must wrap each into
        // its own single-scheme set, preserving order — so the OR list of single-scheme sets mirrors the
        // flat OR list exactly.
        assertEquals(
                List.of(
                        new SecurityRequirementSet(List.of(new SecurityRequirement("bearerAuth", List.of()))),
                        new SecurityRequirementSet(List.of(new SecurityRequirement("apiKey", List.of("read"))))),
                op.securityRequirementSets());
    }

    /**
     * TP-010 (FR-011): a descriptor the adapter builds renders compactly, compares by identity and
     * hashes by identity, and a completion event carrying it can be logged, compared and hashed, all
     * without ever reaching the application's resource instance. Two descriptors adapted from the
     * same meta are distinct: {@code d} and {@code d2}. Events {@code e1} and {@code e2} carry
     * {@code d}; {@code e3} is equal to them except that it carries {@code d2}. Every call runs
     * inside {@code assertDoesNotThrow}, and the resource's counters must all stay at zero.
     *
     * @throws NoSuchMethodException never; the fixture declares the method
     */
    @Test
    @DisplayName("A framework-built descriptor, and an event carrying it, never reach the resource instance when "
            + "logged, compared or hashed")
    void adaptedDescriptorNeverReachesResourceInstance() throws NoSuchMethodException {
        ResourceMethodMeta meta = hostileMeta(hostileResource);
        JaxRsOperationDescriptor d = ResourceMethodMetaToDescriptorAdapter.adapt(meta);
        JaxRsOperationDescriptor d2 = ResourceMethodMetaToDescriptorAdapter.adapt(meta);
        RestRequestCompletedEvent e1 = eventWith(d);
        RestRequestCompletedEvent e2 = eventWith(d);
        RestRequestCompletedEvent e3 = eventWith(d2);

        // One outer assertAll, so every group runs and reports even when an earlier group fails; the
        // counters are read last, after every call.
        assertAll(
                "a framework-built descriptor and an event carrying it",
                () -> assertAll(
                        "logged",
                        () -> assertEquals(
                                HOSTILE_RENDERING, assertDoesNotThrow(d::toString, "d.toString()"), "d.toString()"),
                        () -> {
                            String rendered = assertDoesNotThrow(e1::toString, "e1.toString()");
                            assertTrue(
                                    rendered.contains(EVENT_OPERATION_RENDERING),
                                    () -> "e1.toString() must contain '" + EVENT_OPERATION_RENDERING + "': "
                                            + rendered);
                        }),
                () -> assertAll(
                        "compared",
                        () -> assertTrue(assertDoesNotThrow(() -> d.equals(d), "d.equals(d)"), "d.equals(d)"),
                        () -> assertFalse(assertDoesNotThrow(() -> d.equals(d2), "d.equals(d2)"), "d.equals(d2)"),
                        () -> assertTrue(assertDoesNotThrow(() -> e1.equals(e2), "e1.equals(e2)"), "e1.equals(e2)"),
                        () -> assertFalse(assertDoesNotThrow(() -> e1.equals(e3), "e1.equals(e3)"), "e1.equals(e3)")),
                () -> assertAll(
                        "hashed",
                        () -> assertEquals(
                                System.identityHashCode(d),
                                assertDoesNotThrow(d::hashCode, "d.hashCode()"),
                                "d.hashCode() must be System.identityHashCode(d)"),
                        () -> assertEquals(
                                assertDoesNotThrow(e1::hashCode, "e1.hashCode()"),
                                assertDoesNotThrow(e2::hashCode, "e2.hashCode()"),
                                "e1.hashCode() must equal e2.hashCode()"),
                        () -> assertEquals(
                                2,
                                assertDoesNotThrow(
                                                () -> new HashSet<>(List.of(e1, e2, e3)),
                                                "new HashSet<>(List.of(e1, e2, e3))")
                                        .size(),
                                "e1 and e2 are one element, e3 another")),
                () -> assertAll(
                        "the resource instance was never reached",
                        () -> assertEquals(0, hostileResource.toStringCalls.get(), "HostileResource.toString() calls"),
                        () -> assertEquals(
                                0, hostileResource.equalsCalls.get(), "HostileResource.equals(Object) calls"),
                        () -> assertEquals(
                                0, hostileResource.hashCodeCalls.get(), "HostileResource.hashCode() calls")));
    }

    /**
     * TP-010's event builder: a {@code GET /users/7} 200 completion event carrying {@code operation},
     * with every other component fixed, so two events built with the same operation are equal.
     *
     * @param operation the event's operation
     * @return the event
     */
    private static RestRequestCompletedEvent eventWith(RestOperationDescriptor operation) {
        return new RestRequestCompletedEvent(
                EVENT_START,
                EVENT_END,
                "GET",
                "/users/7",
                operation,
                200,
                null,
                null,
                null,
                null,
                null,
                Optional.empty(),
                Map.of());
    }
}
