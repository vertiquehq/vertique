// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.registry.store;

import java.util.Objects;

/** Immutable decoded source text, fetched separately from metadata. */
public record WorkflowDefinitionDocument(DefinitionKey key, String document, String documentFormat) {
    public WorkflowDefinitionDocument {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(document, "document");
        DefinitionValues.format(documentFormat);
        for (int i = 0; i < document.length(); i++) {
            char c = document.charAt(i);
            if (c == 0) throw new IllegalArgumentException("document contains an actual NUL character");
            if (Character.isHighSurrogate(c)) {
                if (++i >= document.length() || !Character.isLowSurrogate(document.charAt(i)))
                    throw new IllegalArgumentException("document contains an unpaired surrogate");
            } else if (Character.isLowSurrogate(c))
                throw new IllegalArgumentException("document contains an unpaired surrogate");
        }
    }
}
