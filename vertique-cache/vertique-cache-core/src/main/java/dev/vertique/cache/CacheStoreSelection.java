// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache;

import dev.vertique.cache.spi.CacheStore;

/** Internal startup-resolved cache provider selection. */
record CacheStoreSelection(CacheMode mode, String providerId, CacheStore store) {}
