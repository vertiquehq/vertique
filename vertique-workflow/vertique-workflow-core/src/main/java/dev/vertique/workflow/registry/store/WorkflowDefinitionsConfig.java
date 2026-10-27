// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.registry.store;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import dev.vertique.core.exception.ConfigurationException;
import jakarta.annotation.Nullable;

/**
 * Shared document admission policy, plan cache size and publication claim timeout.
 *
 * @param maxDocumentSizeBytes the largest accepted document, in code points; positive, with a representable stored
 *     envelope
 * @param planCacheMaxEntries the most document plans the plan cache keeps resident ({@code
 *     workflow.definitions.planCache.maxEntries}); at least 1, default {@value #DEFAULT_PLAN_CACHE_MAX_ENTRIES}
 * @param buildDefaultScopeDocumentsAtStartup whether startup builds the plan of every live default-scope document
 *     into the plan cache ({@code workflow.definitions.buildDefaultScopeDocumentsAtStartup}), so that a document that
 *     does not build fails startup; default {@code false}, when such a document is built on first use instead
 * @param publicationClaimTimeoutSeconds how long, in seconds, a {@code PUBLISHING} claim is held before another
 *     publish call may take it over and startup resets it to {@code CANDIDATE} ({@code
 *     workflow.definitions.publicationClaimTimeoutSeconds}); at least 1, default
 *     {@value #DEFAULT_PUBLICATION_CLAIM_TIMEOUT_SECONDS}
 */
public record WorkflowDefinitionsConfig(
        int maxDocumentSizeBytes,
        int planCacheMaxEntries,
        boolean buildDefaultScopeDocumentsAtStartup,
        long publicationClaimTimeoutSeconds) {

    /** The default size of the plan cache. */
    public static final int DEFAULT_PLAN_CACHE_MAX_ENTRIES = 500;

    /** The default publication claim timeout, in seconds. */
    public static final long DEFAULT_PUBLICATION_CLAIM_TIMEOUT_SECONDS = 60;

    public WorkflowDefinitionsConfig {
        if (publicationClaimTimeoutSeconds <= 0)
            throw new ConfigurationException("workflow.definitions.publicationClaimTimeoutSeconds must be positive");
        if (maxDocumentSizeBytes <= 0 || maxDocumentSizeBytes + ((long) maxDocumentSizeBytes / 2) > Integer.MAX_VALUE)
            throw new ConfigurationException(
                    "workflow.definitions.maxDocumentSizeBytes must be positive with a representable stored envelope");
        if (planCacheMaxEntries < 1)
            throw new ConfigurationException("workflow.definitions.planCache.maxEntries must be at least 1");
    }

    /**
     * Creates a configuration with the default plan cache size, no default-scope build at startup and the default
     * publication claim timeout.
     *
     * @param maxDocumentSizeBytes the largest accepted document
     */
    public WorkflowDefinitionsConfig(int maxDocumentSizeBytes) {
        this(maxDocumentSizeBytes, DEFAULT_PLAN_CACHE_MAX_ENTRIES, false, DEFAULT_PUBLICATION_CLAIM_TIMEOUT_SECONDS);
    }

    @JsonCreator
    static WorkflowDefinitionsConfig fromJson(
            @JsonProperty("maxDocumentSizeBytes") @Nullable Integer bytes,
            @JsonProperty("planCache") @Nullable JsonNode planCache,
            @JsonProperty("buildDefaultScopeDocumentsAtStartup") @Nullable Boolean buildDefaultScopeDocuments,
            @JsonProperty("publicationClaimTimeoutSeconds") @Nullable Long claimTimeoutSeconds) {
        return new WorkflowDefinitionsConfig(
                bytes == null ? 262144 : bytes,
                planCacheMaxEntries(planCache),
                buildDefaultScopeDocuments != null && buildDefaultScopeDocuments,
                claimTimeoutSeconds == null ? DEFAULT_PUBLICATION_CLAIM_TIMEOUT_SECONDS : claimTimeoutSeconds);
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
        return new WorkflowDefinitionsConfig(262144);
    }

    public int maxStoredDocumentSizeBytes() {
        return (int) (maxDocumentSizeBytes + ((long) maxDocumentSizeBytes / 2));
    }
}
