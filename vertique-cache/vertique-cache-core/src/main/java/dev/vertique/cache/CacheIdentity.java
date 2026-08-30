// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache;

/** Selects the security identity dimensions included in a cache key. */
public enum CacheIdentity {
    NONE,
    ACTOR,
    EFFECTIVE_PRINCIPAL,
    ACTOR_AND_SUBJECT
}
