// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.publication;

import dev.vertique.rest.jaxrs.publication.MountPublication;
import dev.vertique.rest.openapi.docs.document.PublishedDocument;
import dev.vertique.rest.openapi.docs.metadata.OperationFacts;
import io.vertx.core.Context;
import io.vertx.core.Future;
import java.util.Map;
import java.util.Set;

/**
 * Test access to package-private parts of the publication package for tests that live outside it:
 * the inputs the documentation hook derives from a captured publication, so a test assembles that
 * publication exactly as the hook does, and the names a document store holds. Each method delegates
 * to the production member; nothing is reimplemented here.
 */
public final class PublicationAccess {

    private PublicationAccess() {}

    /**
     * Takes the operation facts of a publication, keyed by operation id, as the hook does.
     *
     * @param publication the attached publication
     * @return the facts of each operation
     */
    public static Map<String, OperationFacts> operationFacts(MountPublication publication) {
        return DocsPublicationHook.operationFacts(publication);
    }

    /**
     * Detaches a publication from its descriptors, as the hook does before assembly.
     *
     * @param publication the attached publication
     * @return the detached copy
     */
    public static MountPublication detach(MountPublication publication) {
        return DocsPublicationHook.detach(publication);
    }

    /**
     * Returns the names of the documents a store holds.
     *
     * @param store the document store
     * @return the names of the stored documents
     */
    public static Set<String> names(DocumentStore store) {
        return store.names();
    }

    /**
     * Creates an empty document store, whose constructor is package-private.
     *
     * @return a new store
     */
    public static DocumentStore newStore() {
        return new DocumentStore();
    }

    /**
     * Stores a document under an application name, as the first composition to publish it does.
     *
     * @param store the document store
     * @param name the application name
     * @param caller the context the store assembles on and completes on
     * @param document the document to store
     * @return a future completing once the document is stored
     */
    public static Future<Void> store(DocumentStore store, String name, Context caller, PublishedDocument document) {
        return store.publish(name, caller, () -> document, document::fingerprint);
    }
}
