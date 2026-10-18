// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.core.json.JsonProfileId;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies the type cache: its key, single computation per key, and that failures leave no entry behind. */
class PayloadSchemaCacheTest {

    private static final JsonProfileId VERTIQUE = JsonProfileId.of("vertique");
    private static final JsonProfileId OTHER = JsonProfileId.of("other");

    private final PayloadSchemaCache cache = new PayloadSchemaCache();

    @Test
    @DisplayName("a key is the type name together with the profile id")
    void keysAreTheTypeNameAndTheProfileId() {
        // Given / When / Then: equal components make equal keys, and either component alone distinguishes them
        assertThat(new SchemaKey("a.B", VERTIQUE)).isEqualTo(new SchemaKey("a.B", JsonProfileId.of("vertique")));
        assertThat(new SchemaKey("a.B", VERTIQUE)).isNotEqualTo(new SchemaKey("a.C", VERTIQUE));
        assertThat(new SchemaKey("a.B", VERTIQUE)).isNotEqualTo(new SchemaKey("a.B", OTHER));
        assertThat(new SchemaKey("a.B", VERTIQUE).typeName()).isEqualTo("a.B");
        assertThat(new SchemaKey("a.B", VERTIQUE).profileId()).isEqualTo(VERTIQUE);
    }

    @Test
    @DisplayName("an entry is computed once per key and later lookups return the same instance")
    void anEntryIsComputedOncePerKey() {
        // Given: an empty cache
        SchemaKey key = new SchemaKey("a.B", VERTIQUE);
        AtomicInteger computations = new AtomicInteger();
        assertThat(cache.find(key)).isEmpty();

        // When: the key is requested twice
        CachedPayloadSchema first = cache.computeIfAbsent(key, k -> {
            computations.incrementAndGet();
            return entry();
        });
        CachedPayloadSchema second = cache.computeIfAbsent(key, k -> {
            computations.incrementAndGet();
            return entry();
        });

        // Then: the loader ran once, both calls and find see the one entry
        assertThat(computations).hasValue(1);
        assertThat(second).isSameAs(first);
        assertThat(cache.find(key)).containsSame(first);
        assertThat(cache.snapshot()).containsOnlyKeys(key);
    }

    @Test
    @DisplayName("the same type name under two profiles holds two entries")
    void theSameTypeNameUnderTwoProfilesHoldsTwoEntries() {
        // Given: one type name requested under two profile ids
        CachedPayloadSchema underVertique = cache.computeIfAbsent(new SchemaKey("a.B", VERTIQUE), k -> entry());
        CachedPayloadSchema underOther = cache.computeIfAbsent(new SchemaKey("a.B", OTHER), k -> entry());

        // Then: the entries are distinct and each is found under its own key only
        assertThat(underOther).isNotSameAs(underVertique);
        assertThat(cache.snapshot()).hasSize(2);
        assertThat(cache.find(new SchemaKey("a.B", OTHER))).containsSame(underOther);
    }

    @Test
    @DisplayName("a failing computation propagates and leaves no entry behind")
    void aFailingComputationLeavesNoEntry() {
        // Given: a loader that fails the first time
        SchemaKey key = new SchemaKey("a.B", VERTIQUE);
        AtomicInteger computations = new AtomicInteger();

        // When: the first computation fails
        assertThatThrownBy(() -> cache.computeIfAbsent(key, k -> {
                    computations.incrementAndGet();
                    throw new IllegalStateException("not yet");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("not yet");

        // Then: nothing is cached, and the next request computes again
        assertThat(cache.find(key)).isEmpty();
        assertThat(cache.snapshot()).isEmpty();
        cache.computeIfAbsent(key, k -> {
            computations.incrementAndGet();
            return entry();
        });
        assertThat(computations).hasValue(2);
        assertThat(cache.find(key)).isPresent();
    }

    @Test
    @DisplayName("the snapshot is a copy that later writes do not change")
    void theSnapshotIsACopy() {
        // Given: a snapshot of an empty cache
        Map<SchemaKey, CachedPayloadSchema> before = cache.snapshot();

        // When: an entry is added afterwards
        cache.computeIfAbsent(new SchemaKey("a.B", VERTIQUE), k -> entry());

        // Then: the earlier snapshot is unchanged and cannot be written through
        assertThat(before).isEmpty();
        assertThatThrownBy(() -> cache.snapshot().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("concurrent requests for one key compute it once")
    void concurrentRequestsForOneKeyComputeItOnce() throws Exception {
        // Given: sixteen threads released together on one key
        SchemaKey key = new SchemaKey("a.B", VERTIQUE);
        AtomicInteger computations = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(16);
        try {
            List<Future<CachedPayloadSchema>> results = new ArrayList<>();
            for (int index = 0; index < 16; index++) {
                results.add(executor.submit(() -> {
                    start.await();
                    return cache.computeIfAbsent(key, k -> {
                        computations.incrementAndGet();
                        return entry();
                    });
                }));
            }

            // When: they race
            start.countDown();

            // Then: the loader ran once and every thread received the same entry
            CachedPayloadSchema first = results.get(0).get(10, TimeUnit.SECONDS);
            for (Future<CachedPayloadSchema> result : results) {
                assertThat(result.get(10, TimeUnit.SECONDS)).isSameAs(first);
            }
            assertThat(computations).hasValue(1);
        } finally {
            executor.shutdownNow();
        }
    }

    private static CachedPayloadSchema entry() {
        return Entries.of(new JsonObject().put("type", "object"));
    }
}
