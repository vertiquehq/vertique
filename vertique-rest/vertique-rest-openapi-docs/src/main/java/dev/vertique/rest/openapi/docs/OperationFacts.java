// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import java.util.List;

/**
 * The per-operation descriptor facts the document needs: the consumed media types, and the part
 * names of named file parts. They are taken on the calling thread before the publication is
 * detached, so the descriptor itself is never retained.
 *
 * @param consumes the consumed media types, in declaration order
 * @param namedFileParts the part names of the file parts that have a name, in declaration order
 */
record OperationFacts(List<String> consumes, List<String> namedFileParts) {

    /** Stores unmodifiable copies of both lists; {@code null} lists and elements are rejected. */
    OperationFacts {
        consumes = List.copyOf(consumes);
        namedFileParts = List.copyOf(namedFileParts);
    }
}
