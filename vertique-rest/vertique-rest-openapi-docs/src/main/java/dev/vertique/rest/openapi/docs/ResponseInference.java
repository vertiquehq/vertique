// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import dev.vertique.rest.core.response.ResponseProducerBinding;
import dev.vertique.rest.jaxrs.publication.ResponseShape;
import jakarta.annotation.Nullable;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Set;

/**
 * Classifies the response of one operation's resource method into the kind of response a document
 * can describe.
 */
final class ResponseInference {

    /** The kind of response a resource method produces. */
    enum Row {
        /** The response carries no content. */
        NO_CONTENT,
        /** The response is a JSON entity serialized from the method's return type. */
        JSON_ENTITY,
        /** The response is raw text. */
        RAW_TEXT,
        /** The response is decided at runtime and cannot be described statically. */
        RUNTIME
    }

    /**
     * The classification of one response.
     *
     * @param row the kind of response
     * @param status the published status key, or {@code default} when it is not known
     * @param mediaTypes the media types the response publishes, in published order
     * @param outputType the type the response body is described from, or {@code null} when none
     */
    record Inference(
            Row row,
            String status,
            List<String> mediaTypes,
            @Nullable Type outputType) {

        /**
         * Returns whether the response can be described statically.
         *
         * @return {@code true} for a JSON entity or raw text response
         */
        boolean inferable() {
            return row == Row.JSON_ENTITY || row == Row.RAW_TEXT;
        }
    }

    private ResponseInference() {}

    /**
     * Classifies the response of a resource method.
     *
     * @param shape the response facts of the method
     * @param bindings the registered response producer bindings
     * @return the classification
     */
    static Inference classify(ResponseShape shape, Set<ResponseProducerBinding<?>> bindings) {
        return new Inference(Row.RUNTIME, "default", List.of(), null);
    }
}
