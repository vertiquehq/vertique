// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache;

import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.vertique.cache.config.CacheConfig;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Verifies provider-neutral cache operational-limit rejection at the annotation boundary. */
class CacheOperationalLimitsTest {

    @Test
    void rejectsTtlAboveMaximum() throws NoSuchMethodException {
        var config = new CacheConfig(true, CacheMode.LOCAL, 60, 120, "vertx", 1_024, 1_048_576, 10_000, 100, Map.of());
        var method = Target.class.getDeclaredMethod("value");
        var metadata = CacheTestFixtures.metadata(method, "unused");

        assertThrows(IllegalArgumentException.class, () -> new CacheableAspect(
                        new CacheTestFixtures.RecordingStore(), config, Set.of())
                .interceptor(metadata, method.getAnnotation(Cacheable.class)));
    }

    static final class Target {
        @Cacheable(name = "profile", key = "constant", ttlSeconds = 121)
        String value() {
            return "unused";
        }
    }
}
