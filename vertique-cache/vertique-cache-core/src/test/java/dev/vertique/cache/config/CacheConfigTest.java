// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.cache.CacheMode;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Proof of the documented {@code cache.*} defaults, ranges, and validation rules. */
class CacheConfigTest {

    @Test
    @DisplayName("defaults() carries the documented global values")
    void defaultsCarryTheDocumentedValues() {
        CacheConfig defaults = CacheConfig.defaults();

        assertTrue(defaults.enabled());
        assertEquals(CacheMode.LOCAL, defaults.defaultMode());
        assertEquals(60, defaults.defaultTtlSeconds());
        assertEquals(86_400, defaults.maxTtlSeconds());
        assertEquals(1_024, defaults.maxKeyBytes());
        assertEquals(1_048_576, defaults.maxValueBytes());
        assertEquals(10_000, defaults.maximumEntries());
        assertEquals(100, defaults.backendTimeoutMs());
        assertTrue(defaults.caches().isEmpty());
    }

    @Test
    @DisplayName("cache jsonProfile defaults to the reserved system profile, independent of the edge profile")
    void jsonProfileDefaultsToSystem() {
        assertEquals("system", CacheConfig.defaults().jsonProfile());
    }

    @Test
    @DisplayName("defaults() is itself a valid configuration")
    void defaultsAreValid() {
        CacheConfig defaults = CacheConfig.defaults();

        assertDoesNotThrow(() -> copy(defaults, Map.of()));
    }

    @Test
    @DisplayName("null defaultMode and jsonProfile are rejected")
    void nullRequiredFieldsAreRejected() {
        assertThrows(
                NullPointerException.class,
                () -> new CacheConfig(true, null, 60, 86_400, "system", 1_024, 1_048_576, 10_000, 100, Map.of()));
        assertThrows(
                NullPointerException.class,
                () -> new CacheConfig(
                        true, CacheMode.LOCAL, 60, 86_400, null, 1_024, 1_048_576, 10_000, 100, Map.of()));
        assertThrows(
                NullPointerException.class,
                () -> new CacheConfig(
                        true, CacheMode.LOCAL, 60, 86_400, "system", 1_024, 1_048_576, 10_000, 100, null));
    }

    @Test
    @DisplayName("blank jsonProfile is rejected")
    void blankJsonProfileIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new CacheConfig(
                        true, CacheMode.LOCAL, 60, 86_400, "  ", 1_024, 1_048_576, 10_000, 100, Map.of()));
    }

    @Test
    @DisplayName("negative defaultTtlSeconds is rejected; zero is accepted")
    void defaultTtlRange() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new CacheConfig(
                        true, CacheMode.LOCAL, -1, 86_400, "system", 1_024, 1_048_576, 10_000, 100, Map.of()));
        assertDoesNotThrow(() ->
                new CacheConfig(true, CacheMode.LOCAL, 0, 86_400, "system", 1_024, 1_048_576, 10_000, 100, Map.of()));
    }

    @Test
    @DisplayName("maxTtlSeconds must be positive and cover the default TTL")
    void maxTtlMustCoverDefault() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new CacheConfig(
                        true, CacheMode.LOCAL, 61, 60, "system", 1_024, 1_048_576, 10_000, 100, Map.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new CacheConfig(true, CacheMode.LOCAL, 0, 0, "system", 1_024, 1_048_576, 10_000, 100, Map.of()));
        assertDoesNotThrow(() ->
                new CacheConfig(true, CacheMode.LOCAL, 60, 60, "system", 1_024, 1_048_576, 10_000, 100, Map.of()));
    }

    @Test
    @DisplayName("size limits must be positive")
    void limitsMustBePositive() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new CacheConfig(
                        true, CacheMode.LOCAL, 60, 86_400, "system", 0, 1_048_576, 10_000, 100, Map.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new CacheConfig(true, CacheMode.LOCAL, 60, 86_400, "system", 1_024, 0, 10_000, 100, Map.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new CacheConfig(true, CacheMode.LOCAL, 60, 86_400, "system", 1_024, 1_048_576, 0, 100, Map.of()));
        assertDoesNotThrow(() -> new CacheConfig(true, CacheMode.LOCAL, 60, 86_400, "system", 1, 1, 1, 100, Map.of()));
    }

    @Test
    @DisplayName("backendTimeoutMs is bounded to 1..10000")
    void backendTimeoutRange() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new CacheConfig(
                        true, CacheMode.LOCAL, 60, 86_400, "system", 1_024, 1_048_576, 10_000, 0, Map.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new CacheConfig(
                        true, CacheMode.LOCAL, 60, 86_400, "system", 1_024, 1_048_576, 10_000, 10_001, Map.of()));
        assertDoesNotThrow(() ->
                new CacheConfig(true, CacheMode.LOCAL, 60, 86_400, "system", 1_024, 1_048_576, 10_000, 1, Map.of()));
        assertDoesNotThrow(() -> new CacheConfig(
                true, CacheMode.LOCAL, 60, 86_400, "system", 1_024, 1_048_576, 10_000, 10_000, Map.of()));
    }

    @Test
    @DisplayName("per-cache TTL above maxTtlSeconds is rejected at configuration time")
    void perCacheTtlAboveMaxIsRejected() {
        Map<String, CacheEntryConfig> caches = Map.of("big", new CacheEntryConfig(CacheMode.LOCAL, 86_401, null));

        assertThrows(IllegalArgumentException.class, () -> copy(CacheConfig.defaults(), caches));
        assertDoesNotThrow(
                () -> copy(CacheConfig.defaults(), Map.of("max", new CacheEntryConfig(CacheMode.LOCAL, 86_400, null))));
    }

    @Test
    @DisplayName("blank per-cache names and null entries are rejected")
    void invalidCacheEntriesAreRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> copy(CacheConfig.defaults(), Map.of(" ", new CacheEntryConfig(CacheMode.LOCAL, 1, null))));
        Map<String, CacheEntryConfig> withNull = new HashMap<>();
        withNull.put("orders", null);
        assertThrows(NullPointerException.class, () -> copy(CacheConfig.defaults(), withNull));
    }

    @Test
    @DisplayName("the per-cache map is defensively copied and immutable")
    void cachesMapIsImmutable() {
        Map<String, CacheEntryConfig> source = new HashMap<>();
        source.put("orders", new CacheEntryConfig(CacheMode.LOCAL, 30, null));
        CacheConfig config = copy(CacheConfig.defaults(), source);

        source.put("later", new CacheEntryConfig(CacheMode.LOCAL, 30, null));

        assertEquals(1, config.caches().size());
        assertThrows(UnsupportedOperationException.class, () -> config.caches()
                .put("x", new CacheEntryConfig(CacheMode.LOCAL, 1, null)));
    }

    @Test
    @DisplayName("CacheEntryConfig accepts -1 (inherit), 0 (no expiry), and positive TTLs")
    void entryTtlRange() {
        assertDoesNotThrow(() -> new CacheEntryConfig(CacheMode.LOCAL, -1, null));
        assertDoesNotThrow(() -> new CacheEntryConfig(CacheMode.LOCAL, 0, null));
        assertDoesNotThrow(() -> new CacheEntryConfig(CacheMode.CLUSTERED, 300, "system"));
        assertThrows(IllegalArgumentException.class, () -> new CacheEntryConfig(CacheMode.LOCAL, -2, null));
    }

    @Test
    @DisplayName("CacheEntryConfig requires a mode, allows an absent jsonProfile, and rejects a blank one")
    void entryModeAndProfile() {
        assertThrows(NullPointerException.class, () -> new CacheEntryConfig(null, 1, null));
        assertNull(new CacheEntryConfig(CacheMode.DEFAULT, 1, null).jsonProfile());
        assertThrows(IllegalArgumentException.class, () -> new CacheEntryConfig(CacheMode.LOCAL, 1, " "));
    }

    private static CacheConfig copy(CacheConfig base, Map<String, CacheEntryConfig> caches) {
        return new CacheConfig(
                base.enabled(),
                base.defaultMode(),
                base.defaultTtlSeconds(),
                base.maxTtlSeconds(),
                base.jsonProfile(),
                base.maxKeyBytes(),
                base.maxValueBytes(),
                base.maximumEntries(),
                base.backendTimeoutMs(),
                caches);
    }
}
