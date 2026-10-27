// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.registry.store;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import dev.vertique.core.exception.ConfigurationException;
import jakarta.annotation.Nullable;

/**
 * Shared document admission policy, registry propagation cadence and plan cache size.
 *
 * @param maxStalenessSeconds how long, in seconds, a node may go without reconciling its registry with the table; at
 *     least 1
 * @param maxDocumentSizeBytes the largest accepted document, in code points; positive, with a representable stored
 *     envelope
 * @param planCacheMaxEntries the most document plans the plan cache keeps resident ({@code
 *     workflow.definitions.planCache.maxEntries}); at least 1, default {@value #DEFAULT_PLAN_CACHE_MAX_ENTRIES}
 * @param buildDefaultScopeDocumentsAtStartup whether startup builds the plan of every live default-scope document
 *     into the plan cache ({@code workflow.definitions.buildDefaultScopeDocumentsAtStartup}), so that a document that
 *     does not build fails startup; default {@code false}, when such a document is built on first use instead
 */
public record WorkflowDefinitionsConfig(
        long maxStalenessSeconds,
        int maxDocumentSizeBytes,
        int planCacheMaxEntries,
        boolean buildDefaultScopeDocumentsAtStartup) {

    /** The default size of the plan cache. */
    public static final int DEFAULT_PLAN_CACHE_MAX_ENTRIES = 500;

    public WorkflowDefinitionsConfig {
        if (maxStalenessSeconds <= 0)
            throw new ConfigurationException("workflow.definitions.maxStalenessSeconds must be positive");
        if (maxDocumentSizeBytes <= 0 || maxDocumentSizeBytes + ((long) maxDocumentSizeBytes / 2) > Integer.MAX_VALUE)
            throw new ConfigurationException(
                    "workflow.definitions.maxDocumentSizeBytes must be positive with a representable stored envelope");
        if (planCacheMaxEntries < 1)
            throw new ConfigurationException("workflow.definitions.planCache.maxEntries must be at least 1");
    }

    /**
     * Creates a configuration that builds no default-scope document at startup.
     *
     * @param maxStalenessSeconds how long a node may go without reconciling its registry, in seconds
     * @param maxDocumentSizeBytes the largest accepted document
     * @param planCacheMaxEntries the most document plans the plan cache keeps resident
     */
    public WorkflowDefinitionsConfig(long maxStalenessSeconds, int maxDocumentSizeBytes, int planCacheMaxEntries) {
        this(maxStalenessSeconds, maxDocumentSizeBytes, planCacheMaxEntries, false);
    }

    /**
     * Creates a configuration with the default plan cache size.
     *
     * @param maxStalenessSeconds how long a node may go without reconciling its registry, in seconds
     * @param maxDocumentSizeBytes the largest accepted document
     */
    public WorkflowDefinitionsConfig(long maxStalenessSeconds, int maxDocumentSizeBytes) {
        this(maxStalenessSeconds, maxDocumentSizeBytes, DEFAULT_PLAN_CACHE_MAX_ENTRIES, false);
    }

    @JsonCreator
    static WorkflowDefinitionsConfig fromJson(
            @JsonProperty("maxStalenessSeconds") @Nullable Long seconds,
            @JsonProperty("maxDocumentSizeBytes") @Nullable Integer bytes,
            @JsonProperty("planCache") @Nullable JsonNode planCache,
            @JsonProperty("buildDefaultScopeDocumentsAtStartup") @Nullable Boolean buildDefaultScopeDocuments) {
        return new WorkflowDefinitionsConfig(
                seconds == null ? 30 : seconds,
                bytes == null ? 262144 : bytes,
                planCacheMaxEntries(planCache),
                buildDefaultScopeDocuments != null && buildDefaultScopeDocuments);
    }

    private static int planCacheMaxEntries(@Nullable JsonNode planCache) {
        if (planCache == null || planCache.isNull()) return DEFAULT_PLAN_CACHE_MAX_ENTRIES;
        if (!planCache.isObject()) throw new ConfigurationException("workflow.definitions.planCache must be an object");
        JsonNode entries = planCache.get("maxEntries");
        if (entries == null || entries.isNull()) return DEFAULT_PLAN_CACHE_MAX_ENTRIES;
        if (!entries.isIntegralNumber() || !entries.canConvertToInt())
            throw new ConfigurationException("workflow.definitions.planCache.maxEntries must be an integer");
        return entries.intValue();
    }

    public static WorkflowDefinitionsConfig defaults() {
        return new WorkflowDefinitionsConfig(30, 262144, DEFAULT_PLAN_CACHE_MAX_ENTRIES, false);
    }

    public int maxStoredDocumentSizeBytes() {
        return (int) (maxDocumentSizeBytes + ((long) maxDocumentSizeBytes / 2));
    }
}
