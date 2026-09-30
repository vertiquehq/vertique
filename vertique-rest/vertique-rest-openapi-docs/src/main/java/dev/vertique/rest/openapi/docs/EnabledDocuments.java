// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import dev.vertique.rest.jaxrs.publication.RestApplications.ContractOrigin;
import java.util.List;
import java.util.Optional;

/**
 * The documents enabled for one component, ordered by application name.
 *
 * @param all the enabled documents
 */
record EnabledDocuments(List<EnabledDocument> all) {

    /**
     * Copies the document list unmodifiably.
     *
     * @param all the enabled documents
     */
    EnabledDocuments {
        all = List.copyOf(all);
    }

    /**
     * Finds an enabled document by application name.
     *
     * @param name the application name
     * @return the document, or empty when none is enabled under that name
     */
    Optional<EnabledDocument> byName(String name) {
        return all.stream().filter(document -> document.name().equals(name)).findFirst();
    }

    /**
     * Reports whether no document is enabled.
     *
     * @return {@code true} when {@link #all()} is empty
     */
    boolean isEmpty() {
        return all.isEmpty();
    }

    /**
     * One enabled document.
     *
     * @param name the application name
     * @param declaringType the declaring interface
     * @param access the access policy of the document routes
     * @param mountPath the mount path of the application
     * @param contractOrigin where the application's contract comes from
     * @param info the {@code info} object of the document
     */
    record EnabledDocument(
            String name,
            Class<?> declaringType,
            ApiDocs.Access access,
            String mountPath,
            ContractOrigin contractOrigin,
            InfoConfig info) {}
}
