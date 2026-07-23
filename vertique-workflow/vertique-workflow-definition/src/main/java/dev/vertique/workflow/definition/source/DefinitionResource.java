// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.source;

import dev.vertique.workflow.definition.parser.DocumentFormat;
import java.util.Arrays;
import java.util.Objects;

/**
 * A single workflow definition resource — the raw bytes of a definition document, the format
 * hint that tells the parser how to deserialize them, and the provenance metadata.
 *
 * <p>The {@code content} array is defensively copied on construction so that callers cannot
 * mutate the bytes after submitting the resource to the pipeline.
 *
 * @param content raw bytes of the definition document (YAML or JSON); must not be {@code null}
 * @param format the serialization format of {@code content}; must not be {@code null}
 * @param metadata provenance information for audit and management surfaces; must not be
 *     {@code null}
 */
public record DefinitionResource(byte[] content, DocumentFormat format, SourceMetadata metadata) {

    /**
     * Compact constructor: validates non-null invariants and stores a defensive copy of
     * {@code content}.
     *
     * @throws NullPointerException if any component is {@code null}
     */
    public DefinitionResource {
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(metadata, "metadata");
        content = Arrays.copyOf(content, content.length);
    }

    /**
     * Returns a defensive copy of the raw content bytes so that callers cannot mutate the
     * internal state of this record.
     *
     * @return a copy of the content bytes; never {@code null}
     */
    @Override
    public byte[] content() {
        return Arrays.copyOf(content, content.length);
    }
}
