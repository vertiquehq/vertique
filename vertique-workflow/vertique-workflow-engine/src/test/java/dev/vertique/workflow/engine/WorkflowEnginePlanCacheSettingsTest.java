// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.cache.CacheMode;
import dev.vertique.cache.config.CacheEntryConfig;
import dev.vertique.workflow.engine.registry.PlanCache;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The plan cache's size and expiry as read from its {@code cache.caches.workflow-plans} entry. */
class WorkflowEnginePlanCacheSettingsTest {

    @Test
    @DisplayName("without an entry the plan cache holds 500 plans and never expires them")
    void absentEntryGivesTheDefaults() {
        assertThat(WorkflowEngineModule.planCacheMaxEntries(Optional.empty())).isEqualTo(500);
        assertThat(PlanCache.DEFAULT_MAX_ENTRIES).isEqualTo(500);
        assertThat(WorkflowEngineModule.planCacheExpiry(Optional.empty())).isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName(
            "an entry without maximumEntries keeps the default size, and an omitted ttlSeconds (0) means no expiry")
    void entryWithoutMaximumEntriesKeepsTheDefaultSize() {
        Optional<CacheEntryConfig> entry = Optional.of(new CacheEntryConfig(CacheMode.LOCAL, 0, null));

        assertThat(WorkflowEngineModule.planCacheMaxEntries(entry)).isEqualTo(500);
        assertThat(WorkflowEngineModule.planCacheExpiry(entry)).isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("an entry's maximumEntries and positive ttlSeconds size and expire the plan cache")
    void entrySizesAndExpiresTheCache() {
        Optional<CacheEntryConfig> entry = Optional.of(new CacheEntryConfig(CacheMode.LOCAL, 90, null, 12));

        assertThat(WorkflowEngineModule.planCacheMaxEntries(entry)).isEqualTo(12);
        assertThat(WorkflowEngineModule.planCacheExpiry(entry)).isEqualTo(Duration.ofSeconds(90));
    }

    @Test
    @DisplayName("ttlSeconds -1 (inherit) does not give the plan cache the global default TTL")
    void inheritedTtlMeansNoExpiry() {
        Optional<CacheEntryConfig> entry = Optional.of(new CacheEntryConfig(CacheMode.DEFAULT, -1, null, 3));

        assertThat(WorkflowEngineModule.planCacheExpiry(entry)).isEqualTo(Duration.ZERO);
    }
}
