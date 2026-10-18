// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.registry.store;

import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.Objects;

/** Immutable source attribution; URIs are opaque and are never fetched by the store. */
public record DefinitionProvenance(
        String sourceType,
        @Nullable String uri,
        @Nullable String revision,
        @Nullable String author,
        @Nullable String uploadId,
        Instant loadedAt) {
    public DefinitionProvenance {
        DefinitionValues.text(sourceType, "sourceType", 64, true);
        DefinitionValues.text(revision, "revision", 255, false);
        DefinitionValues.text(author, "author", 255, false);
        DefinitionValues.text(uploadId, "uploadId", 255, false);
        Objects.requireNonNull(loadedAt, "loadedAt");
    }
}
