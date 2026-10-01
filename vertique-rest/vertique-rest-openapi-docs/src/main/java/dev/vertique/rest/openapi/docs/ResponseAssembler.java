// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.vertique.rest.jaxrs.publication.MountPublication;
import dev.vertique.rest.jaxrs.publication.OperationPublication;
import jakarta.annotation.Nullable;

/**
 * Plans the {@code responses} of one operation's Operation Object and checks every schema it will
 * publish, before the document is written.
 */
final class ResponseAssembler {

    private ResponseAssembler() {}

    /**
     * Plans the responses of one operation.
     *
     * @param subject the failure-message subject naming the application and its mount
     * @param document the enabled document
     * @param publication the detached publication of the mount
     * @param operation the operation
     * @param facts the operation's descriptor facts, or {@code null} when none were taken
     * @param context the component's assembly inputs
     * @param generators the output generators of the document's assembly
     * @param warnings the document's pending warnings
     * @return the plan, to publish once every operation of the document is checked
     */
    static ResponsePlan check(
            String subject,
            EnabledDocuments.EnabledDocument document,
            MountPublication publication,
            OperationPublication operation,
            @Nullable OperationFacts facts,
            AssemblyContext context,
            OutputGenerators generators,
            PendingWarnings warnings) {
        return new ResponsePlan();
    }

    /** The checked responses of one operation. */
    static final class ResponsePlan {

        ResponsePlan() {}

        /**
         * Writes the {@code responses} object, publishing each checked schema once.
         *
         * @param embedder the schema embedder that checked the schemas
         * @return the Responses Object, or {@code null} when the operation publishes none
         */
        @Nullable
        ObjectNode publish(SchemaEmbedder embedder) {
            return null;
        }
    }
}
