// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SystemIdentities}.
 *
 * <p>Verifies: all three factory methods ({@code workflow}, {@code scheduledJob}, {@code internal})
 * produce a {@link SecurityIdentity} with {@link PrincipalType#SYSTEM} actor; the actor id matches
 * the expected stable prefix; the {@code system.reason} attribute is populated from the reason
 * argument; blank and null reason arguments are rejected with {@link IllegalArgumentException};
 * the class is {@code final} and has only a private constructor (cannot be instantiated
 * externally).
 */
class SystemIdentitiesTest {

    // --- workflow factory ---

    @Test
    @DisplayName("workflow(reason) returns SYSTEM identity")
    void workflowReturnsSystemIdentity() {
        SecurityIdentity identity = SystemIdentities.workflow("background-reconciliation");
        assertEquals(PrincipalType.SYSTEM, identity.actor().type());
    }

    @Test
    @DisplayName("workflow(reason) actor id contains \"workflow\"")
    void workflowActorIdContainsWorkflow() {
        SecurityIdentity identity = SystemIdentities.workflow("audit-compaction");
        assertTrue(
                identity.actor().id().contains("workflow"),
                "actor id should contain 'workflow' but was: "
                        + identity.actor().id());
    }

    @Test
    @DisplayName("workflow(reason) stamps system.reason attribute")
    void workflowStampsSystemReasonAttribute() {
        SecurityIdentity identity = SystemIdentities.workflow("audit-compaction");
        assertEquals("audit-compaction", identity.actor().attributes().get("system.reason"));
    }

    @Test
    @DisplayName("workflow(null reason) throws IllegalArgumentException")
    void workflowNullReasonThrowsIae() {
        assertThrows(IllegalArgumentException.class, () -> SystemIdentities.workflow(null));
    }

    @Test
    @DisplayName("workflow(blank reason) throws IllegalArgumentException")
    void workflowBlankReasonThrowsIae() {
        assertThrows(IllegalArgumentException.class, () -> SystemIdentities.workflow("   "));
    }

    @Test
    @DisplayName("workflow(empty reason) throws IllegalArgumentException")
    void workflowEmptyReasonThrowsIae() {
        assertThrows(IllegalArgumentException.class, () -> SystemIdentities.workflow(""));
    }

    // --- scheduledJob factory ---

    @Test
    @DisplayName("scheduledJob(reason) returns SYSTEM identity")
    void scheduledJobReturnsSystemIdentity() {
        SecurityIdentity identity = SystemIdentities.scheduledJob("nightly-cleanup");
        assertEquals(PrincipalType.SYSTEM, identity.actor().type());
    }

    @Test
    @DisplayName("scheduledJob(reason) actor id contains \"scheduledJob\"")
    void scheduledJobActorIdContainsScheduledJob() {
        SecurityIdentity identity = SystemIdentities.scheduledJob("nightly-cleanup");
        assertTrue(
                identity.actor().id().contains("scheduledJob"),
                "actor id should contain 'scheduledJob' but was: "
                        + identity.actor().id());
    }

    @Test
    @DisplayName("scheduledJob(reason) stamps system.reason attribute")
    void scheduledJobStampsSystemReasonAttribute() {
        SecurityIdentity identity = SystemIdentities.scheduledJob("nightly-cleanup");
        assertEquals("nightly-cleanup", identity.actor().attributes().get("system.reason"));
    }

    @Test
    @DisplayName("scheduledJob(null reason) throws IllegalArgumentException")
    void scheduledJobNullReasonThrowsIae() {
        assertThrows(IllegalArgumentException.class, () -> SystemIdentities.scheduledJob(null));
    }

    @Test
    @DisplayName("scheduledJob(blank reason) throws IllegalArgumentException")
    void scheduledJobBlankReasonThrowsIae() {
        assertThrows(IllegalArgumentException.class, () -> SystemIdentities.scheduledJob("  "));
    }

    // --- internal factory ---

    @Test
    @DisplayName("internal(reason) returns SYSTEM identity")
    void internalReturnsSystemIdentity() {
        SecurityIdentity identity = SystemIdentities.internal("delayed-job-retry");
        assertEquals(PrincipalType.SYSTEM, identity.actor().type());
    }

    @Test
    @DisplayName("internal(reason) actor id contains \"internal\"")
    void internalActorIdContainsInternal() {
        SecurityIdentity identity = SystemIdentities.internal("outbox-redispatch");
        assertTrue(
                identity.actor().id().contains("internal"),
                "actor id should contain 'internal' but was: "
                        + identity.actor().id());
    }

    @Test
    @DisplayName("internal(reason) stamps system.reason attribute")
    void internalStampsSystemReasonAttribute() {
        SecurityIdentity identity = SystemIdentities.internal("outbox-redispatch");
        assertEquals("outbox-redispatch", identity.actor().attributes().get("system.reason"));
    }

    @Test
    @DisplayName("internal(null reason) throws IllegalArgumentException")
    void internalNullReasonThrowsIae() {
        assertThrows(IllegalArgumentException.class, () -> SystemIdentities.internal(null));
    }

    @Test
    @DisplayName("internal(blank reason) throws IllegalArgumentException")
    void internalBlankReasonThrowsIae() {
        assertThrows(IllegalArgumentException.class, () -> SystemIdentities.internal(""));
    }

    // --- class is final ---

    @Test
    @DisplayName("SystemIdentities class is final")
    void classIsFinal() {
        assertTrue(Modifier.isFinal(SystemIdentities.class.getModifiers()), "SystemIdentities must be final");
    }

    // --- constructor is private (utility class cannot be instantiated) ---

    @Test
    @DisplayName("SystemIdentities has exactly one declared constructor and it is private")
    void constructorIsPrivate() {
        Constructor<?>[] constructors = SystemIdentities.class.getDeclaredConstructors();
        assertEquals(1, constructors.length, "SystemIdentities should declare exactly one constructor");
        assertTrue(Modifier.isPrivate(constructors[0].getModifiers()), "SystemIdentities constructor must be private");
    }
}
