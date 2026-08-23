// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache;

/** Placement semantics for a cache store, independent of any provider technology. */
public enum CacheMode {
    DEFAULT,
    LOCAL,
    CLUSTERED
}
