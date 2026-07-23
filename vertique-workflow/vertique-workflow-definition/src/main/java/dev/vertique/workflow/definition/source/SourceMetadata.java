// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.source;

import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.Objects;

/**
 * Immutable provenance record that describes where a workflow definition document was loaded from.
 *
 * <p>Source metadata is stored alongside the compiled definition in the
 * {@link dev.vertique.workflow.definition.service.WorkflowDefinitionStore} for management and
 * audit surfaces. It never crosses into the runtime engine — the engine sees only
 * {@code WorkflowPlan} and callbacks (FR-WF-DEF-026).
 *
 * @param sourceType a non-blank, application-defined label for the origin type (e.g., {@code "file"},
 *     {@code "cms"}, {@code "upload"}); used to classify the source in management UIs
 * @param uri optional URI identifying the exact resource (e.g., file path, S3 key, CMS URL);
 *     may be {@code null} when not applicable
 * @param revision optional revision string (e.g., git commit SHA, CMS content version);
 *     may be {@code null}
 * @param author optional identity of the person or system that provided the definition;
 *     may be {@code null}
 * @param uploadId optional opaque reference used by upload-based sources to correlate the
 *     upload request; may be {@code null}
 * @param loadedAt the instant at which the resource was ingested into the pipeline; must not
 *     be {@code null}
 */
public record SourceMetadata(
        String sourceType,
        @Nullable String uri,
        @Nullable String revision,
        @Nullable String author,
        @Nullable String uploadId,
        Instant loadedAt) {

    /**
     * Compact constructor: validates that {@code sourceType} is non-null and non-blank, and that
     * {@code loadedAt} is non-null.
     *
     * @throws NullPointerException if {@code sourceType} or {@code loadedAt} is null
     * @throws IllegalArgumentException if {@code sourceType} is blank
     */
    public SourceMetadata {
        Objects.requireNonNull(sourceType, "sourceType");
        if (sourceType.isBlank()) {
            throw new IllegalArgumentException("sourceType must not be blank");
        }
        Objects.requireNonNull(loadedAt, "loadedAt");
    }
}
