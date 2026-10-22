// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.registry.store;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.vertique.core.exception.ConfigurationException;
import jakarta.annotation.Nullable;

/** Shared document admission policy and registry propagation cadence. */
public record WorkflowDefinitionsConfig(long maxStalenessSeconds, int maxDocumentSizeBytes) {
    public WorkflowDefinitionsConfig {
        if (maxStalenessSeconds <= 0)
            throw new ConfigurationException("workflow.definitions.maxStalenessSeconds must be positive");
        if (maxDocumentSizeBytes <= 0 || maxDocumentSizeBytes + ((long) maxDocumentSizeBytes / 2) > Integer.MAX_VALUE)
            throw new ConfigurationException(
                    "workflow.definitions.maxDocumentSizeBytes must be positive with a representable stored envelope");
    }

    @JsonCreator
    static WorkflowDefinitionsConfig fromJson(
            @JsonProperty("maxStalenessSeconds") @Nullable Long seconds,
            @JsonProperty("maxDocumentSizeBytes") @Nullable Integer bytes) {
        return new WorkflowDefinitionsConfig(seconds == null ? 30 : seconds, bytes == null ? 262144 : bytes);
    }

    public static WorkflowDefinitionsConfig defaults() {
        return new WorkflowDefinitionsConfig(30, 262144);
    }

    public int maxStoredDocumentSizeBytes() {
        return (int) (maxDocumentSizeBytes + ((long) maxDocumentSizeBytes / 2));
    }
}
