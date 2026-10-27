// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.registry;

import dev.vertique.cache.spi.CacheObserver;
import dev.vertique.cache.spi.event.CacheEvent;
import dev.vertique.cache.spi.event.CacheOperation;
import dev.vertique.cache.spi.event.CacheOperationCompleted;
import dev.vertique.cache.spi.event.CacheOutcome;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Keeps every cache operation event it is handed, so a test can count them by cache, operation and outcome. */
final class RecordingCacheObserver implements CacheObserver {

    private final List<CacheOperationCompleted> events = new CopyOnWriteArrayList<>();

    @Override
    public void onEvent(CacheEvent event) {
        if (event instanceof CacheOperationCompleted completed) events.add(completed);
    }

    /** How many events of the cache carried the operation and the outcome. */
    long count(String cacheName, CacheOperation operation, CacheOutcome outcome) {
        return matching(cacheName, operation, outcome).size();
    }

    /** How many events of the cache carried the outcome, whatever the operation. */
    long count(String cacheName, CacheOutcome outcome) {
        return events.stream()
                .filter(event -> event.cacheName().equals(cacheName) && event.outcome() == outcome)
                .count();
    }

    /** The elapsed times of the events of the cache that carried the operation and the outcome. */
    List<Duration> elapsed(String cacheName, CacheOperation operation, CacheOutcome outcome) {
        return matching(cacheName, operation, outcome).stream()
                .map(CacheOperationCompleted::elapsed)
                .toList();
    }

    /** Every event seen so far, in arrival order. */
    List<CacheOperationCompleted> events() {
        return List.copyOf(events);
    }

    private List<CacheOperationCompleted> matching(String cacheName, CacheOperation operation, CacheOutcome outcome) {
        return events.stream()
                .filter(event -> event.cacheName().equals(cacheName)
                        && event.operation() == operation
                        && event.outcome() == outcome)
                .toList();
    }
}
