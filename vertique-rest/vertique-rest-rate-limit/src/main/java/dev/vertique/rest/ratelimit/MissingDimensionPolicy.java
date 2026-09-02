// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.ratelimit;

/**
 * Governs how an edge {@link RateLimitEdgeRule} treats a request whose {@code HEADER} dimension
 * value is absent, or present more than once (contracts/rest-adapter.md, "Rule composition
 * semantics"). A repeated header is always treated as missing — never keyed on its first
 * occurrence.
 */
public enum MissingDimensionPolicy {
    /** All missing-header callers for this rule share one bucket. Default. */
    SHARED_BUCKET,
    /** Skip this rule entirely for the request; other rules still apply. */
    BYPASS
}
