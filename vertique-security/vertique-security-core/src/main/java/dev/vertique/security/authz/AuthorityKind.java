// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.authz;

/**
 * Discriminator for the semantic category of an {@link AuthorityClaim}.
 *
 * <p>Each constant classifies a claim by how it was granted and how it should be
 * interpreted by authorization policy evaluators.
 *
 * <ul>
 *   <li>{@link #ROLE} — coarse-grained group membership (e.g., {@code "admin"}, {@code "user"})</li>
 *   <li>{@link #GROUP} — organizational or directory group membership</li>
 *   <li>{@link #SCOPE} — OAuth 2.0 delegated-access scope (e.g., {@code "read"}, {@code "write"})</li>
 *   <li>{@link #PERMISSION} — fine-grained action right (e.g., {@code "orders:create"})</li>
 *   <li>{@link #ENTITLEMENT} — product/feature entitlement from a licensing or subscription service</li>
 *   <li>{@link #CLAIM} — raw token claim that does not fit a more specific category</li>
 * </ul>
 */
public enum AuthorityKind {

    /** Coarse-grained membership role (e.g., {@code "admin"}, {@code "manager"}). */
    ROLE,

    /** Organizational or directory group membership (e.g., {@code "payments-team"}). */
    GROUP,

    /** OAuth 2.0 delegated-access scope (e.g., {@code "read"}, {@code "payments:write"}). */
    SCOPE,

    /** Fine-grained action permission (e.g., {@code "orders:create"}, {@code "items:read"}). */
    PERMISSION,

    /** Product or feature entitlement from a licensing or subscription system. */
    ENTITLEMENT,

    /** Raw token or identity claim that does not fit a more specific category. */
    CLAIM
}
