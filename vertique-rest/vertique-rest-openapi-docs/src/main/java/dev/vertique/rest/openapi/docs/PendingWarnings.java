// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The warnings one document's assembly collects, held back until the document is written.
 *
 * <p>An assembly adds each warning as it reaches the content the warning is about, so the warnings
 * are held in document order. Only once the document is fully assembled and written does {@link #emit}
 * hand them, in that order, to the component's {@link DocumentWarnings}, which logs each at most once
 * per kind and document; an assembly that fails publication warns about nothing.
 */
final class PendingWarnings {

    private final String documentName;
    private final List<Warning> warnings = new ArrayList<>();

    /**
     * Creates an empty collection for one document.
     *
     * @param documentName the document's application name
     */
    PendingWarnings(String documentName) {
        this.documentName = Objects.requireNonNull(documentName, "documentName");
    }

    /**
     * Holds a warning back until the document is written.
     *
     * @param kind the warning kind, a fixed identifier that may carry an operation id and an input
     * @param message the complete warning message, starting with the document's configuration path
     */
    void add(String kind, String message) {
        warnings.add(new Warning(Objects.requireNonNull(kind, "kind"), Objects.requireNonNull(message, "message")));
    }

    /**
     * Logs every held warning, in the order they were added, each unless a warning of the same kind
     * was already logged for the document.
     *
     * @param target the component's warnings
     */
    void emit(DocumentWarnings target) {
        for (Warning warning : warnings) {
            target.warnOnce(warning.kind(), documentName, warning.message());
        }
    }

    /**
     * One held warning.
     *
     * @param kind the warning kind
     * @param message the complete warning message
     */
    private record Warning(String kind, String message) {}
}
