// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.injvm;

import dev.vertique.cache.config.CacheConfig;
import dev.vertique.cache.spi.CacheStore;

/** Runs the shared CacheStore contract against the local provider. */
class CacheStoreContractTest extends dev.vertique.cache.CacheStoreContractTest {
    @Override
    protected CacheStore createStore() {
        return new CaffeineCacheStore(CacheConfig.defaults());
    }
}
