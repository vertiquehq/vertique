// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache;

import dev.vertique.cache.spi.CacheStore;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Map;
import javax.inject.Provider;

/** Resolves one explicitly installed provider for each semantic cache mode. */
@Singleton
final class CacheStoreResolver {
    private final Map<CacheMode, Provider<CacheStore>> stores;
    private final Map<CacheMode, String> providerIds;

    @Inject
    CacheStoreResolver(Map<CacheMode, Provider<CacheStore>> stores, Map<CacheMode, String> providerIds) {
        this.stores = Map.copyOf(stores);
        this.providerIds = Map.copyOf(providerIds);
        validateBindings();
    }

    private void validateBindings() {
        for (CacheMode mode : CacheMode.values()) {
            if (mode == CacheMode.DEFAULT) {
                if (stores.containsKey(mode) || providerIds.containsKey(mode)) {
                    throw new IllegalStateException("cache provider bindings cannot target DEFAULT mode");
                }
                continue;
            }
            boolean hasStore = stores.containsKey(mode);
            boolean hasProviderId = providerIds.containsKey(mode);
            if (hasStore != hasProviderId) {
                throw new IllegalStateException("cache provider binding is incomplete for mode " + mode);
            }
            if (hasProviderId && !validProviderId(providerIds.get(mode))) {
                throw new IllegalStateException("cache provider id is invalid for mode " + mode);
            }
        }
    }

    private static boolean validProviderId(String id) {
        return id != null && id.matches("[a-z][a-z0-9-]{0,31}") && !id.equals("none");
    }

    CacheStoreSelection resolve(CacheMode mode) {
        if (mode == null || mode == CacheMode.DEFAULT) {
            throw new IllegalArgumentException("cache mode must be resolved before provider lookup");
        }
        Provider<CacheStore> provider = stores.get(mode);
        String providerId = providerIds.get(mode);
        if (provider == null || providerId == null) {
            throw new IllegalStateException("no cache provider is installed for mode " + mode);
        }
        return new CacheStoreSelection(mode, providerId, provider.get());
    }

    static CacheStoreResolver fixed(CacheStore store) {
        String providerId = store.getClass().getSimpleName().contains("Redis") ? "redis" : "caffeine";
        return new CacheStoreResolver(
                Map.of(CacheMode.LOCAL, () -> store, CacheMode.CLUSTERED, () -> store),
                Map.of(CacheMode.LOCAL, providerId, CacheMode.CLUSTERED, providerId));
    }
}
