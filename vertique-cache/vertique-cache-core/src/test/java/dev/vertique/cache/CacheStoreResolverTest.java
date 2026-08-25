// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.Test;

class CacheStoreResolverTest {

    @Test
    void incompleteProviderModeBindingFailsDuringConstruction() {
        assertThrows(
                IllegalStateException.class,
                () -> new CacheStoreResolver(Map.of(CacheMode.LOCAL, () -> null), Map.of()));
    }

    @Test
    void defaultModeProviderBindingFailsDuringConstruction() {
        assertThrows(
                IllegalStateException.class,
                () -> new CacheStoreResolver(
                        Map.of(CacheMode.DEFAULT, () -> null), Map.of(CacheMode.DEFAULT, "invalid")));
    }
}
