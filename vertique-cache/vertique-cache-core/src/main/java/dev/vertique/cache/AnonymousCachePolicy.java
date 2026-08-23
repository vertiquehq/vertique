// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache;

/** Defines whether identity-scoped cache calls may use a separate anonymous bucket. */
public enum AnonymousCachePolicy {
    BYPASS,
    CACHE_AS_ANONYMOUS
}
