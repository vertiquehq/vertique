// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import dev.vertique.rest.jaxrs.publication.MountPublication;
import java.util.Map;

/**
 * Assembles one application's document from its detached mount publication.
 *
 * <p>Failures are thrown as {@link dev.vertique.rest.core.RestConfigurationException} so that
 * publication fails startup.
 */
final class DocumentAssembler {

    private DocumentAssembler() {}

    /**
     * Assembles the document of one application.
     *
     * @param document the enabled document of the application
     * @param publication the detached publication of the application's mount
     * @param facts the per-operation descriptor facts, keyed by operation id
     * @return the published document
     */
    static PublishedDocument assemble(
            EnabledDocuments.EnabledDocument document,
            MountPublication publication,
            Map<String, OperationFacts> facts) {
        return DocumentWriter.write(document.info(), SnapshotRenderer.render(publication));
    }
}
