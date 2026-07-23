// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable metadata describing a delegated-authorization context carried in a
 * {@link SecurityIdentity}.
 *
 * <p>Delegation occurs when one principal acts on behalf of another under an explicit authority
 * grant (e.g. PSD2/PIS consent, admin impersonation policy). The {@code kind} field names the
 * delegation scheme, and {@code authorityId} identifies the specific grant or policy that
 * authorized the delegation.
 *
 * <p>Construction rules:
 * <ul>
 *   <li>{@code kind} and {@code authorityId} are required (non-null, non-blank)</li>
 *   <li>{@code reason} must be a non-null {@link Optional} — {@link Optional#empty()} is valid</li>
 *   <li>A null {@code attributes} map is treated as {@link Map#of()} (empty)</li>
 *   <li>The attributes map is defensively copied</li>
 * </ul>
 *
 * @param kind        name of the delegation scheme (e.g. {@code "psd2-pis"},
 *                    {@code "impersonation"})
 * @param authorityId identifier of the specific authority grant or policy (e.g. a consent ID,
 *                    policy version)
 * @param reason      optional human-readable description of why delegation was granted
 * @param attributes  additional provenance attributes for this delegation
 */
public record DelegationContext(
        String kind, String authorityId, Optional<String> reason, Map<String, Object> attributes) {

    /**
     * The reserved {@link #kind()} value for a framework-mediated deferred-execution scheduling
     * relationship (see {@code IdentityReconstruction#deferredExecution} /
     * {@code CapturedAuthorityReconstruction#deferredExecutionWithCapturedAuthority}) — never a
     * grant-backed delegation scheme such as {@code "psd2-pis"} or {@code "impersonation"}.
     *
     * <p>Per FR-ID-DG-006, v1 delegation enforcement (actor-authority &cap; grant-scope) applies
     * only to genuinely grant-backed delegation. A {@code DelegationContext} carrying this kind
     * represents framework-scheduled work resuming under the executing service's own authority
     * (resolved live by Mode 2) — not a grant to validate. An
     * {@link dev.vertique.security.authz.AuthorizationNarrower} enforcing grant-scope intersection
     * MUST treat this kind as framework-mediated scheduling and pass it through unchanged, never as
     * a grant id to look up.
     */
    public static final String DEFERRED_EXECUTION_KIND = "deferred-execution";

    /**
     * Compact constructor — validates required fields and defensively copies the attributes map.
     */
    public DelegationContext {
        Objects.requireNonNull(kind, "kind");
        if (kind.isBlank()) {
            throw new IllegalArgumentException("kind must not be blank");
        }
        Objects.requireNonNull(authorityId, "authorityId");
        if (authorityId.isBlank()) {
            throw new IllegalArgumentException("authorityId must not be blank");
        }
        Objects.requireNonNull(reason, "reason");
        attributes = Map.copyOf(attributes == null ? Map.of() : attributes);
    }
}
