// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.rest.core.events.RestRequestCompletedEvent;
import dev.vertique.rest.core.routing.RestOperationDescriptor;
import dev.vertique.rest.core.security.SecurityPolicy;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TestOperationDescriptors#of(String, String, String)} (rest-025 T003, TP-011).
 *
 * <p>Verifies that the fixture builds a {@link RestOperationDescriptor} carrying only its identity:
 * <ul>
 *   <li>the three identity accessors return the arguments, and every other member is empty: no media
 *       types, no security requirement sets, no annotations, a {@link SecurityPolicy.None} policy and an
 *       empty {@code findAnnotation}; reading the effective security policy does not throw;</li>
 *   <li>its {@code toString} is the compact {@code <httpMethod> <routeTemplate> (<operationId>)} form, and
 *       it compares by identity: two calls with the same arguments return distinct, unequal instances, with
 *       no value-equality promise (ruling R2);</li>
 *   <li>it works as the {@code operation} of a {@link RestRequestCompletedEvent}, whose {@code toString}
 *       renders it as {@code <operationId> <routeTemplate>};</li>
 *   <li>a {@code null} in any argument position is rejected with a {@link NullPointerException}.</li>
 * </ul>
 *
 * <p>The descriptor is checked for {@code null} before anything reads it, so a fixture that returns
 * {@code null} fails on an assertion rather than on a {@link NullPointerException}.
 */
class TestOperationDescriptorsTest {

    /** The HTTP method every descriptor here is built with. */
    private static final String HTTP_METHOD = "GET";

    /** The route template every descriptor here is built with. */
    private static final String ROUTE_TEMPLATE = "/users/{id}";

    /** The operation identifier every descriptor here is built with. */
    private static final String OPERATION_ID = "getUser";

    /** Start time of the event built here. */
    private static final Instant START_TIME = Instant.parse("2026-01-01T00:00:00Z");

    /** End time of the event built here. */
    private static final Instant END_TIME = START_TIME.plusMillis(5);

    /** TP-011: the fixture's descriptor carries only its identity and compares by identity. */
    @Test
    @DisplayName(
            "of builds a descriptor carrying only its identity, compared by identity, rejecting null identity values")
    void ofBuildsDescriptorCarryingOnlyItsIdentity() {
        RestOperationDescriptor descriptor = TestOperationDescriptors.of(HTTP_METHOD, ROUTE_TEMPLATE, OPERATION_ID);

        assertNotNull(descriptor, "of returns a descriptor");

        RestOperationDescriptor second = TestOperationDescriptors.of(HTTP_METHOD, ROUTE_TEMPLATE, OPERATION_ID);
        RestRequestCompletedEvent event = eventWith(descriptor);

        // One outer assertAll, so a red reports every failing facet at once.
        assertAll(
                "descriptor built by of",
                () -> assertAll(
                        "identity members",
                        () -> assertEquals(HTTP_METHOD, descriptor.httpMethod(), "httpMethod() returns the argument"),
                        () -> assertEquals(
                                ROUTE_TEMPLATE, descriptor.routeTemplate(), "routeTemplate() returns the argument"),
                        () -> assertEquals(
                                OPERATION_ID, descriptor.operationId(), "operationId() returns the argument")),
                () -> assertAll(
                        "every other member is empty",
                        () -> assertEquals(List.of(), descriptor.consumes(), "consumes() is empty"),
                        () -> assertEquals(List.of(), descriptor.produces(), "produces() is empty"),
                        () -> assertEquals(
                                List.of(), descriptor.securityRequirementSets(), "securityRequirementSets() is empty"),
                        () -> assertEquals(List.of(), descriptor.methodAnnotations(), "methodAnnotations() is empty"),
                        () -> assertEquals(List.of(), descriptor.classAnnotations(), "classAnnotations() is empty"),
                        () -> assertInstanceOf(
                                SecurityPolicy.None.class,
                                descriptor.securityPolicy(),
                                "securityPolicy() is None: no security annotations"),
                        () -> assertEquals(
                                Optional.empty(),
                                descriptor.findAnnotation(Deprecated.class),
                                "findAnnotation(Deprecated.class) is empty"),
                        () -> assertDoesNotThrow(
                                descriptor::effectiveSecurityPolicy, "effectiveSecurityPolicy() does not throw")),
                () -> assertAll(
                        "rendering and equality (ruling R2)",
                        () -> assertEquals(
                                HTTP_METHOD + " " + ROUTE_TEMPLATE + " (" + OPERATION_ID + ")",
                                descriptor.toString(),
                                "toString() is '<httpMethod> <routeTemplate> (<operationId>)'"),
                        () -> assertNotSame(descriptor, second, "each call returns a new instance"),
                        () -> assertNotEquals(
                                descriptor, second, "descriptors compare by identity, with no value-equality promise")),
                () -> assertTrue(
                        event.toString().contains(OPERATION_ID + " " + ROUTE_TEMPLATE),
                        () -> "as an event's operation, it renders as '<operationId> <routeTemplate>': " + event),
                () -> assertAll(
                        "a null identity value is rejected",
                        () -> assertThrows(
                                NullPointerException.class,
                                () -> TestOperationDescriptors.of(null, ROUTE_TEMPLATE, OPERATION_ID),
                                "a null httpMethod throws NullPointerException"),
                        () -> assertThrows(
                                NullPointerException.class,
                                () -> TestOperationDescriptors.of(HTTP_METHOD, null, OPERATION_ID),
                                "a null routeTemplate throws NullPointerException"),
                        () -> assertThrows(
                                NullPointerException.class,
                                () -> TestOperationDescriptors.of(HTTP_METHOD, ROUTE_TEMPLATE, null),
                                "a null operationId throws NullPointerException")));
    }

    /**
     * Builds an event carrying {@code operation}, with every other component valid: the method
     * {@link #HTTP_METHOD}, path {@code /users/7}, status 200, no failure, no security or correlation
     * snapshot, no origin and no attributes.
     *
     * @param operation the operation component
     * @return the event
     */
    private static RestRequestCompletedEvent eventWith(RestOperationDescriptor operation) {
        return new RestRequestCompletedEvent(
                START_TIME,
                END_TIME,
                HTTP_METHOD,
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
