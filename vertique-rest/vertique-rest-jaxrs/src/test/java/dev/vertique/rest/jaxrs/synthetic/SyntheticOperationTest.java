// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.synthetic;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.security.authz.AccessPolicy;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Unit proofs for {@link SyntheticOperation}'s factories: each builds an operation carrying every
 * value given, including the documented application's name, each rejects a {@code null} argument
 * with a {@link NullPointerException} naming it, and {@code withRoles} refuses an empty role list or
 * a blank role with an {@link IllegalArgumentException} naming {@code rolesAllowed}. {@code
 * withPolicy} carries the complete typed policy as given, without judging it, and the two earlier
 * factories carry none.
 */
class SyntheticOperationTest {

    private static final String ORIGIN = "@ApiDocs on application 'management'";
    private static final String OPERATION_ID = "apidocs:management:json";
    private static final String SCHEME = "bearerAuth";
    private static final String APPLICATION = "management";

    private static Stream<Arguments> cases() {
        return Stream.of(
                Arguments.of("authenticated builds with every given value", (Executable)
                        SyntheticOperationTest::assertAuthenticatedBuiltWithGivenValues),
                Arguments.of("withRoles builds with every given value", (Executable)
                        SyntheticOperationTest::assertWithRolesBuiltWithGivenValues),
                Arguments.of("withRoles refuses an empty role list", (Executable) () -> assertRolesRejected(List.of())),
                Arguments.of("withRoles refuses a blank role", (Executable)
                        () -> assertRolesRejected(List.of("admin", " "))),
                Arguments.of("authenticated refuses a null origin", (Executable) () -> assertNullRejected(
                        "origin", () -> SyntheticOperation.authenticated(null, OPERATION_ID, SCHEME, APPLICATION))),
                Arguments.of("authenticated refuses a null operationId", (Executable) () -> assertNullRejected(
                        "operationId", () -> SyntheticOperation.authenticated(ORIGIN, null, SCHEME, APPLICATION))),
                Arguments.of("authenticated refuses a null schemeName", (Executable) () -> assertNullRejected(
                        "schemeName", () -> SyntheticOperation.authenticated(ORIGIN, OPERATION_ID, null, APPLICATION))),
                Arguments.of("authenticated refuses a null applicationName", (Executable) () -> assertNullRejected(
                        "applicationName", () -> SyntheticOperation.authenticated(ORIGIN, OPERATION_ID, SCHEME, null))),
                Arguments.of("withRoles refuses a null origin", (Executable) () -> assertNullRejected(
                        "origin",
                        () -> SyntheticOperation.withRoles(null, OPERATION_ID, SCHEME, APPLICATION, List.of("admin")))),
                Arguments.of("withRoles refuses a null operationId", (Executable) () -> assertNullRejected(
                        "operationId",
                        () -> SyntheticOperation.withRoles(ORIGIN, null, SCHEME, APPLICATION, List.of("admin")))),
                Arguments.of("withRoles refuses a null schemeName", (Executable) () -> assertNullRejected(
                        "schemeName",
                        () -> SyntheticOperation.withRoles(ORIGIN, OPERATION_ID, null, APPLICATION, List.of("admin")))),
                Arguments.of("withRoles refuses a null applicationName", (Executable) () -> assertNullRejected(
                        "applicationName",
                        () -> SyntheticOperation.withRoles(ORIGIN, OPERATION_ID, SCHEME, null, List.of("admin")))),
                Arguments.of("withRoles refuses a null rolesAllowed", (Executable) () -> assertNullRejected(
                        "rolesAllowed",
                        () -> SyntheticOperation.withRoles(ORIGIN, OPERATION_ID, SCHEME, APPLICATION, null))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("The factories build operations and refuse an empty role list or a blank role")
    void factoriesBuildOperationsAndRefuseInvalidRoleLists(String label, Executable action) throws Throwable {
        action.execute();
    }

    private static void assertAuthenticatedBuiltWithGivenValues() {
        SyntheticOperation operation = SyntheticOperation.authenticated(ORIGIN, OPERATION_ID, SCHEME, APPLICATION);
        assertEquals(ORIGIN, operation.origin());
        assertEquals(OPERATION_ID, operation.operationId());
        assertEquals(SCHEME, operation.schemeName());
        assertEquals(APPLICATION, operation.applicationName());
        assertEquals(Optional.empty(), operation.rolesAllowed());
    }

    private static void assertWithRolesBuiltWithGivenValues() {
        SyntheticOperation operation =
                SyntheticOperation.withRoles(ORIGIN, OPERATION_ID, SCHEME, APPLICATION, List.of("admin", "ops"));
        assertEquals(ORIGIN, operation.origin());
        assertEquals(OPERATION_ID, operation.operationId());
        assertEquals(SCHEME, operation.schemeName());
        assertEquals(APPLICATION, operation.applicationName());
        assertEquals(Optional.of(List.of("admin", "ops")), operation.rolesAllowed());
    }

    private static void assertRolesRejected(List<String> roles) {
        IllegalArgumentException thrown = assertThrows(
                IllegalArgumentException.class,
                () -> SyntheticOperation.withRoles(ORIGIN, OPERATION_ID, SCHEME, APPLICATION, roles));
        assertTrue(
                thrown.getMessage() != null && thrown.getMessage().contains("rolesAllowed"),
                "message must name 'rolesAllowed' but was: " + thrown.getMessage());
    }

    private static void assertNullRejected(String argumentName, Executable factoryCall) {
        NullPointerException thrown = assertThrows(NullPointerException.class, factoryCall);
        assertEquals(argumentName, thrown.getMessage(), "message must be exactly the argument's name");
    }

    @Test
    @DisplayName("A typed operation carries its complete policy and the legacy factories carry none")
    void shouldCarryTheCompleteTypedPolicy() {
        List<Class<? extends AccessPolicy>> policies = List.of(
                TypedPolicies.Public.class,
                TypedPolicies.Deny.class,
                TypedPolicies.Authenticated.class,
                TypedPolicies.Roles.class,
                TypedPolicies.Scopes.class,
                TypedPolicies.Action.class,
                TypedPolicies.RolesAndAction.class,
                TypedPolicies.Combined.class);

        // Given each supported policy form, with a scheme and with the empty scheme
        // When a synthetic operation is created from it through withPolicy
        // Then the operation answers the policy class it was given and every other value unchanged
        for (Class<? extends AccessPolicy> policy : policies) {
            for (String scheme : List.of(SCHEME, "")) {
                SyntheticOperation operation =
                        SyntheticOperation.withPolicy(ORIGIN, OPERATION_ID, scheme, APPLICATION, policy);
                String label = policy.getSimpleName() + " with scheme '" + scheme + "'";
                assertAll(
                        label,
                        () -> assertEquals(Optional.of(policy), operation.accessPolicy(), "accessPolicy"),
                        () -> assertEquals(ORIGIN, operation.origin(), "origin"),
                        () -> assertEquals(OPERATION_ID, operation.operationId(), "operationId"),
                        () -> assertEquals(scheme, operation.schemeName(), "schemeName"),
                        () -> assertEquals(APPLICATION, operation.applicationName(), "applicationName"),
                        () -> assertEquals(Optional.empty(), operation.rolesAllowed(), "rolesAllowed"));
            }
        }

        // And a policy the installer would refuse is still carried: the factory does not judge it
        @SuppressWarnings("unchecked")
        Class<? extends AccessPolicy> notAPolicy = (Class<? extends AccessPolicy>) (Class<?>) AccessPolicy.class;
        assertEquals(
                Optional.of(notAPolicy),
                SyntheticOperation.withPolicy(ORIGIN, OPERATION_ID, SCHEME, APPLICATION, notAPolicy)
                        .accessPolicy());

        // When a required argument is null
        // Then the factory refuses it with a NullPointerException naming the argument
        Class<? extends AccessPolicy> policy = TypedPolicies.Roles.class;
        assertAll(
                "null arguments",
                () -> assertNullRejected(
                        "origin", () -> SyntheticOperation.withPolicy(null, OPERATION_ID, SCHEME, APPLICATION, policy)),
                () -> assertNullRejected(
                        "operationId", () -> SyntheticOperation.withPolicy(ORIGIN, null, SCHEME, APPLICATION, policy)),
                () -> assertNullRejected(
                        "schemeName",
                        () -> SyntheticOperation.withPolicy(ORIGIN, OPERATION_ID, null, APPLICATION, policy)),
                () -> assertNullRejected(
                        "applicationName",
                        () -> SyntheticOperation.withPolicy(ORIGIN, OPERATION_ID, SCHEME, null, policy)),
                () -> assertNullRejected(
                        "policy",
                        () -> SyntheticOperation.withPolicy(ORIGIN, OPERATION_ID, SCHEME, APPLICATION, null)));

        // When an operation is created through a legacy factory
        // Then it carries no typed policy and its roles are exactly as before
        SyntheticOperation authenticated = SyntheticOperation.authenticated(ORIGIN, OPERATION_ID, SCHEME, APPLICATION);
        SyntheticOperation withRoles =
                SyntheticOperation.withRoles(ORIGIN, OPERATION_ID, SCHEME, APPLICATION, List.of("admin", "ops"));
        assertAll(
                "legacy factories",
                () -> assertEquals(Optional.empty(), authenticated.accessPolicy(), "authenticated accessPolicy"),
                () -> assertEquals(Optional.empty(), authenticated.rolesAllowed(), "authenticated rolesAllowed"),
                () -> assertEquals(Optional.empty(), withRoles.accessPolicy(), "withRoles accessPolicy"),
                () -> assertEquals(Optional.of(List.of("admin", "ops")), withRoles.rolesAllowed(), "withRoles roles"));
    }
}
