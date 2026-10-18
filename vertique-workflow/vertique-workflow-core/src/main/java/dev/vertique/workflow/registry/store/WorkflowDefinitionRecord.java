// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.registry.store;

import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.Objects;

/** Metadata projection that never carries document source text. */
public record WorkflowDefinitionRecord(
        DefinitionKey key,
        DefinitionKind kind,
        String planHash,
        DefinitionStatus status,
        DefinitionProvenance provenance,
        @Nullable String documentFormat,
        Instant createdAt,
        Instant statusChangedAt) {
    public WorkflowDefinitionRecord {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(provenance, "provenance");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(statusChangedAt, "statusChangedAt");
        if (planHash == null || !planHash.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("planHash must be 64 lowercase hexadecimal characters");
        if (kind == DefinitionKind.DOCUMENT) DefinitionValues.format(documentFormat);
        else if (documentFormat != null || key.tenantKey() != null)
            throw new IllegalArgumentException("CODE requires global scope and no document format");
    }
}
