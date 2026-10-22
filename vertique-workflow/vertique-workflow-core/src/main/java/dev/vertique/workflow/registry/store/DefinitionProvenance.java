// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.registry.store;

import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.Objects;

/**
 * Immutable source attribution; URIs are opaque and are never fetched by the store.
 *
 * <p>Every text component must be storable: free of NUL and unpaired surrogates (a supplementary character is
 * valid) and within its bound in code points. The source type is required, nonblank and at most 64 code points;
 * the uri is optional and at most 2048 code points; the revision, author and upload id are optional and at most
 * 255 code points each. A value that breaks a rule is rejected with {@link IllegalArgumentException}.
 *
 * @param sourceType the kind of source the definition came from
 * @param uri an opaque locator of the source, or {@code null}
 * @param revision the source revision, or {@code null}
 * @param author who provided the definition, or {@code null}
 * @param uploadId the upload the definition arrived with, or {@code null}
 * @param loadedAt when the definition was loaded
 */
public record DefinitionProvenance(
        String sourceType,
        @Nullable String uri,
        @Nullable String revision,
        @Nullable String author,
        @Nullable String uploadId,
        Instant loadedAt) {
    public DefinitionProvenance {
        DefinitionValues.text(sourceType, "sourceType", 64, true);
        DefinitionValues.text(uri, "uri", 2048, false);
        DefinitionValues.text(revision, "revision", 255, false);
        DefinitionValues.text(author, "author", 255, false);
        DefinitionValues.text(uploadId, "uploadId", 255, false);
        Objects.requireNonNull(loadedAt, "loadedAt");
    }
}
