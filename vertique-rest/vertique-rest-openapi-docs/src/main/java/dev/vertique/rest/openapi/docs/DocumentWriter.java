// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Writes the OpenAPI document of an application in its JSON and YAML forms and computes the entity
 * tag of each form.
 *
 * <p>The tree holds {@code openapi}, {@code info}, and {@code paths}, in the field order of the
 * OpenAPI 3.1.1 specification's tables: the root fields as {@code openapi}, {@code info}, {@code
 * paths}, and the info fields as {@code title}, {@code description}, {@code version}. The JSON form
 * is compact UTF-8. The YAML form is written from the same tree with the default settings of
 * Jackson's YAML factory, which quotes every string, so parsing it gives a tree equal to the JSON
 * tree. Equal inputs give equal bytes.
 */
final class DocumentWriter {

    /** The OpenAPI version every document declares. */
    static final String OPENAPI_VERSION = "3.1.1";

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private DocumentWriter() {}

    /**
     * Writes a document.
     *
     * @param info the {@code info} object of the document
     * @param snapshot the snapshot of the mount the document is assembled from
     * @return the document in both forms, with the entity tag of each
     * @throws IllegalStateException when the tree cannot be serialized
     */
    static PublishedDocument write(InfoConfig info, Snapshot snapshot) {
        ObjectNode infoNode = JSON.createObjectNode();
        infoNode.put("title", info.title());
        if (info.description() != null) {
            infoNode.put("description", info.description());
        }
        infoNode.put("version", info.version());
        ObjectNode root = JSON.createObjectNode();
        root.put("openapi", OPENAPI_VERSION);
        root.set("info", infoNode);
        root.set("paths", JSON.createObjectNode());
        try {
            byte[] json = JSON.writeValueAsBytes(root);
            byte[] yaml = YAML.writeValueAsBytes(root);
            return new PublishedDocument(json, yaml, entityTag(json), entityTag(yaml), snapshot);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("The OpenAPI document cannot be serialized", e);
        }
    }

    /**
     * Computes the strong entity tag of a form: a double quote, the lowercase hexadecimal SHA-256 of
     * the form's bytes, and a double quote.
     *
     * @param bytes the bytes of one form
     * @return the entity tag
     */
    static String entityTag(byte[] bytes) {
        try {
            return '"'
                    + HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
                    + '"';
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
