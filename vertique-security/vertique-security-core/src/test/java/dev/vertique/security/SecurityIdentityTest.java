// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SecurityIdentity}.
 *
 * <p>Verifies: minimal (actor-only) construction; full PSD2/PIS shape construction; null actor
 * rejected with NPE; null Optional arguments rejected; factory methods {@code user}, {@code
 * service}, and {@code anonymous}; type enforcement on factories; absence of a {@code system}
 * factory (verified reflectively).
 */
class SecurityIdentityTest {

    private static final PrincipalRef USER_PRINCIPAL = new PrincipalRef(PrincipalType.USER, "user-42", Map.of());
    private static final PrincipalRef SERVICE_PRINCIPAL =
            new PrincipalRef(PrincipalType.SERVICE, "svc-payments", Map.of());
    private static final PrincipalRef SYSTEM_PRINCIPAL =
            new PrincipalRef(PrincipalType.SYSTEM, "system:workflow", Map.of());
    private static final PrincipalRef ANON_PRINCIPAL = new PrincipalRef(PrincipalType.ANONYMOUS, "anonymous", Map.of());

    // --- minimal (actor-only) construction ---

    @Test
    @DisplayName("constructs with actor only; subject/delegation/client are Optional.empty()")
    void actorOnlyConstruction() {
        SecurityIdentity identity =
                new SecurityIdentity(USER_PRINCIPAL, Optional.empty(), Optional.empty(), Optional.empty());
        assertEquals(USER_PRINCIPAL, identity.actor());
        assertFalse(identity.subject().isPresent());
        assertFalse(identity.delegation().isPresent());
        assertFalse(identity.client().isPresent());
    }

    // --- full PSD2/PIS shape ---

    @Test
    @DisplayName("constructs full PSD2/PIS shape with actor=SERVICE, subject=USER, delegation, client")
    void fullPsd2PisShape() {
        PrincipalRef tppActor = new PrincipalRef(PrincipalType.SERVICE, "tpp-example", Map.of());
        PrincipalRef psuSubject = new PrincipalRef(PrincipalType.USER, "psu-user-99", Map.of());
        DelegationContext delegation =
                new DelegationContext("psd2-pis", "consent-xyz", Optional.of("Payment initiation"), Map.of());
        ClientRef client = new ClientRef("tpp-example-oauth", "jwt-azp", Map.of());

        SecurityIdentity identity =
                new SecurityIdentity(tppActor, Optional.of(psuSubject), Optional.of(delegation), Optional.of(client));

        assertEquals(PrincipalType.SERVICE, identity.actor().type());
        assertEquals("tpp-example", identity.actor().id());

        assertTrue(identity.subject().isPresent());
        assertEquals(PrincipalType.USER, identity.subject().get().type());
        assertEquals("psu-user-99", identity.subject().get().id());

        assertTrue(identity.delegation().isPresent());
        assertEquals("psd2-pis", identity.delegation().get().kind());

        assertTrue(identity.client().isPresent());
        assertEquals("tpp-example-oauth", identity.client().get().clientId());
    }

    // --- null actor rejection ---

    @Test
    @DisplayName("null actor throws NullPointerException")
    void nullActorThrowsNpe() {
        assertThrows(
                NullPointerException.class,
                () -> new SecurityIdentity(null, Optional.empty(), Optional.empty(), Optional.empty()));
    }

    // --- null Optional arguments rejected ---

    @Test
    @DisplayName("null Optional for subject throws NullPointerException")
    void nullOptionalSubjectThrowsNpe() {
        assertThrows(
                NullPointerException.class,
                () -> new SecurityIdentity(USER_PRINCIPAL, null, Optional.empty(), Optional.empty()));
    }

    @Test
    @DisplayName("null Optional for delegation throws NullPointerException")
    void nullOptionalDelegationThrowsNpe() {
        assertThrows(
                NullPointerException.class,
                () -> new SecurityIdentity(USER_PRINCIPAL, Optional.empty(), null, Optional.empty()));
    }

    @Test
    @DisplayName("null Optional for client throws NullPointerException")
    void nullOptionalClientThrowsNpe() {
        assertThrows(
                NullPointerException.class,
                () -> new SecurityIdentity(USER_PRINCIPAL, Optional.empty(), Optional.empty(), null));
    }

    // --- factory: user ---

    @Test
    @DisplayName("user(actor) returns identity with USER actor; subject/delegation/client empty")
    void factoryUser() {
        SecurityIdentity identity = SecurityIdentity.user(USER_PRINCIPAL);
        assertNotNull(identity);
        assertEquals(PrincipalType.USER, identity.actor().type());
        assertEquals(USER_PRINCIPAL, identity.actor());
        assertFalse(identity.subject().isPresent());
        assertFalse(identity.delegation().isPresent());
        assertFalse(identity.client().isPresent());
    }

    @Test
    @DisplayName("user() factory rejects non-USER PrincipalRef")
    void factoryUserRejectsNonUserType() {
        assertThrows(IllegalArgumentException.class, () -> SecurityIdentity.user(SERVICE_PRINCIPAL));
    }

    @Test
    @DisplayName("user() factory rejects SYSTEM PrincipalRef")
    void factoryUserRejectsSystem() {
        assertThrows(IllegalArgumentException.class, () -> SecurityIdentity.user(SYSTEM_PRINCIPAL));
    }

    @Test
    @DisplayName("user() factory rejects ANONYMOUS PrincipalRef")
    void factoryUserRejectsAnonymous() {
        assertThrows(IllegalArgumentException.class, () -> SecurityIdentity.user(ANON_PRINCIPAL));
    }

    // --- factory: service ---

    @Test
    @DisplayName("service(actor) returns identity with SERVICE actor; subject/delegation/client empty")
    void factoryService() {
        SecurityIdentity identity = SecurityIdentity.service(SERVICE_PRINCIPAL);
        assertNotNull(identity);
        assertEquals(PrincipalType.SERVICE, identity.actor().type());
        assertEquals(SERVICE_PRINCIPAL, identity.actor());
        assertFalse(identity.subject().isPresent());
        assertFalse(identity.delegation().isPresent());
        assertFalse(identity.client().isPresent());
    }

    @Test
    @DisplayName("service() factory rejects non-SERVICE PrincipalRef")
    void factoryServiceRejectsNonServiceType() {
        assertThrows(IllegalArgumentException.class, () -> SecurityIdentity.service(USER_PRINCIPAL));
    }

    @Test
    @DisplayName("service() factory rejects SYSTEM PrincipalRef")
    void factoryServiceRejectsSystem() {
        assertThrows(IllegalArgumentException.class, () -> SecurityIdentity.service(SYSTEM_PRINCIPAL));
    }

    // --- factory: anonymous ---

    @Test
    @DisplayName("anonymous() returns identity with ANONYMOUS actor type")
    void factoryAnonymous() {
        SecurityIdentity identity = SecurityIdentity.anonymous();
        assertNotNull(identity);
        assertEquals(PrincipalType.ANONYMOUS, identity.actor().type());
    }

    @Test
    @DisplayName("anonymous() actor id is a stable constant \"anonymous\"")
    void factoryAnonymousActorIdIsStable() {
        SecurityIdentity identity = SecurityIdentity.anonymous();
        assertEquals("anonymous", identity.actor().id());
    }

    @Test
    @DisplayName("anonymous() returns identity with no subject, delegation, or client")
    void factoryAnonymousHasNoSubjectOrDelegation() {
        SecurityIdentity identity = SecurityIdentity.anonymous();
        assertFalse(identity.subject().isPresent());
        assertFalse(identity.delegation().isPresent());
        assertFalse(identity.client().isPresent());
    }

    // --- no system() factory ---

    @Test
    @DisplayName("SecurityIdentity does NOT expose a public static system() method")
    void noSystemFactoryMethod() {
        boolean hasSystemFactory = Arrays.stream(SecurityIdentity.class.getMethods())
                .filter(m -> Modifier.isStatic(m.getModifiers()))
                .filter(m -> m.getName().equals("system"))
                .anyMatch(m -> true);
        assertFalse(
                hasSystemFactory,
                "SecurityIdentity must not expose a public static 'system' factory method; "
                        + "use SystemIdentities instead");
    }

    @Test
    @DisplayName("SecurityIdentity has no public static method named system (broader check)")
    void noSystemMethodAtAll() {
        long systemMethods = Arrays.stream(SecurityIdentity.class.getDeclaredMethods())
                .filter(m -> Modifier.isStatic(m.getModifiers()))
                .filter(m -> Modifier.isPublic(m.getModifiers()))
                .filter(m -> m.getName().equals("system"))
                .count();
        assertEquals(0, systemMethods, "SecurityIdentity.system(...) must not exist; use SystemIdentities");
    }
}
