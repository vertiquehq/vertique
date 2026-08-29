// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.spi.event;

/** The cache runtime operation an event describes. */
public enum CacheOperation {
    GET,
    PUT,
    EVICT,
    CLEAR
}
