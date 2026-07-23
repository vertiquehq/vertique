// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable, audit-safe record of a delegation grant: an explicit authority delegation from a
 * {@code grantor} principal to a {@code grantee} principal over a bounded scope, valid until
 * {@code expiresAt}.
 *
 * <p>A grant answers "who may act, on whose behalf, over what, until when" — never "why", and never
 * carries the evidence that established the grant. {@link #evidenceRef()} is a <strong>reference</strong>
 * string (e.g. a consent-record id, an approval-workflow id) pointing at evidence held elsewhere; it
 * MUST NOT carry credential material, tokens, or the evidence content itself. This mirrors the
 * credential-free discipline {@link IdentitySnapshot} enforces for durable identity carriage
 * (FR-ID-CA-003 / NFR-ID2-003): a {@code DelegationGrant} is safe to include verbatim in audit output.
 *
 * <p>Grant-backed delegation is carried on the existing {@link DelegationContext#authorityId()} — the
 * grant id, and only the grant id. No parallel delegation representation is introduced
 * (PRD identity-002 FR-ID-DG-001).
 *
 * <p>Evaluating whether a grant currently authorizes an (actor, subject, scope) triple is the job of
 * {@link DelegationGrantValidator}, not this record — a {@code DelegationGrant} is a pure value.
 *
 * @param grantId     stable identifier for this grant; also the value carried in
 *                    {@link DelegationContext#authorityId()} for grant-backed delegation
 * @param grantor     the principal who granted authority — the "on whose behalf" principal, i.e. the
 *                    delegated evaluation's subject
 * @param grantee     the principal who received authority — the principal that acts, i.e. the
 *                    delegated evaluation's actor
 * @param scopeKind   the namespaced kind of the scope this grant bounds (e.g. {@code "cms.content"});
 *                    opaque to the framework
 * @param scopeRef    the scope value within {@code scopeKind} (e.g. a content id); opaque to the
 *                    framework
 * @param expiresAt   the instant this grant stops authorizing anything; never {@code null}
 * @param evidenceRef an opaque reference to the evidence that established this grant (e.g. a consent
 *                    record id); never the evidence itself
 */
public record DelegationGrant(
        String grantId,
        PrincipalRef grantor,
        PrincipalRef grantee,
        String scopeKind,
        String scopeRef,
        Instant expiresAt,
        String evidenceRef) {

    /**
     * Compact constructor — validates required fields.
     */
    public DelegationGrant {
        requireNonBlank(grantId, "grantId");
        Objects.requireNonNull(grantor, "grantor");
        Objects.requireNonNull(grantee, "grantee");
        requireNonBlank(scopeKind, "scopeKind");
        requireNonBlank(scopeRef, "scopeRef");
        Objects.requireNonNull(expiresAt, "expiresAt");
        requireNonBlank(evidenceRef, "evidenceRef");
    }

    /**
     * Validates that a required {@code String} field is neither {@code null} nor blank.
     *
     * @param value the value to validate
     * @param name  the field name, used in the thrown exception's message
     * @throws NullPointerException     if {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code value} is blank
     */
    private static void requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
