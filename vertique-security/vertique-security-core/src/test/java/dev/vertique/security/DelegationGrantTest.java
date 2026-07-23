// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DelegationGrant} — the immutable, audit-safe value record backing grant-based
 * delegation (PRD identity-002 FR-ID-DG-002).
 */
class DelegationGrantTest {

    private static final PrincipalRef GRANTOR = new PrincipalRef(PrincipalType.USER, "user-1", Map.of());
    private static final PrincipalRef GRANTEE = new PrincipalRef(PrincipalType.SERVICE, "svc-1", Map.of());

    // --- audit-safety contract ---

    @Test
    @DisplayName("DelegationGrant carries only reference fields — no credential/evidence material, and "
            + "evidenceRef is a plain reference String")
    void auditSafeNoEvidence() {
        Set<String> expectedComponents =
                Set.of("grantId", "grantor", "grantee", "scopeKind", "scopeRef", "expiresAt", "evidenceRef");
        RecordComponent[] components = DelegationGrant.class.getRecordComponents();

        assertEquals(
                expectedComponents.size(),
                components.length,
                "DelegationGrant must carry exactly the frozen §14.3 component set — no additional "
                        + "fields were added");

        for (RecordComponent component : components) {
            assertTrue(
                    expectedComponents.contains(component.getName()),
                    "unexpected DelegationGrant component: " + component.getName());
            String typeName = component.getType().getName().toLowerCase();
            assertTrue(
                    !typeName.contains("evidence") && !typeName.contains("token") && !typeName.contains("credential"),
                    "DelegationGrant must not carry a credential/evidence-bearing typed component, found: "
                            + component.getName() + " : " + component.getType());
        }

        RecordComponent evidenceRefComponent = Arrays.stream(components)
                .filter(c -> c.getName().equals("evidenceRef"))
                .findFirst()
                .orElseThrow();
        assertEquals(
                String.class,
                evidenceRefComponent.getType(),
                "evidenceRef must be a plain reference String, never a richer evidence-carrying type");
    }

    // --- compact constructor validation ---

    @Test
    @DisplayName("compact constructor rejects a blank grantId")
    void rejectsBlankGrantId() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new DelegationGrant(" ", GRANTOR, GRANTEE, "cms.content", "article-42", Instant.now(), "ref"));
    }

    @Test
    @DisplayName("compact constructor rejects a null grantor")
    void rejectsNullGrantor() {
        assertThrows(
                NullPointerException.class,
                () -> new DelegationGrant("grant-1", null, GRANTEE, "cms.content", "article-42", Instant.now(), "ref"));
    }

    @Test
    @DisplayName("compact constructor rejects a null grantee")
    void rejectsNullGrantee() {
        assertThrows(
                NullPointerException.class,
                () -> new DelegationGrant("grant-1", GRANTOR, null, "cms.content", "article-42", Instant.now(), "ref"));
    }

    @Test
    @DisplayName("compact constructor rejects a blank scopeKind")
    void rejectsBlankScopeKind() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new DelegationGrant("grant-1", GRANTOR, GRANTEE, " ", "article-42", Instant.now(), "ref"));
    }

    @Test
    @DisplayName("compact constructor rejects a blank scopeRef")
    void rejectsBlankScopeRef() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new DelegationGrant("grant-1", GRANTOR, GRANTEE, "cms.content", " ", Instant.now(), "ref"));
    }

    @Test
    @DisplayName("compact constructor rejects a null expiresAt")
    void rejectsNullExpiresAt() {
        assertThrows(
                NullPointerException.class,
                () -> new DelegationGrant("grant-1", GRANTOR, GRANTEE, "cms.content", "article-42", null, "ref"));
    }

    @Test
    @DisplayName("compact constructor rejects a blank evidenceRef")
    void rejectsBlankEvidenceRef() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new DelegationGrant(
                        "grant-1", GRANTOR, GRANTEE, "cms.content", "article-42", Instant.now(), " "));
    }
}
