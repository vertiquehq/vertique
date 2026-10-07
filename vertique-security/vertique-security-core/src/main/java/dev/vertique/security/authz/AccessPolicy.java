// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.authz;

/**
 * Marker for a typed access policy.
 *
 * <p>A valid policy is a public, non-generic interface that extends only this marker and declares
 * no fields, methods, or nested types. The requirements live on the interface as direct runtime
 * security annotations. This type is not itself a policy, and nothing instantiates a policy.
 */
public interface AccessPolicy {}
