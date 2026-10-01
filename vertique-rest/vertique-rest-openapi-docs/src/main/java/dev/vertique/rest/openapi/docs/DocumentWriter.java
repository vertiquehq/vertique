// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.Nullable;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/**
 * Writes the OpenAPI document of an application in its JSON and YAML forms and computes the entity
 * tag of each form.
 *
 * <p>The document tree is built by {@link DocumentAssembler}; this class writes it with its members
 * in insertion order and builds the {@code info} object: from a configured {@code info}, with the
 * fields {@code title}, {@code description}, {@code version}; from the declaring interface's
 * annotation, with every set member in the OpenAPI 3.1.1 field order. The JSON form is compact UTF-8. The YAML form is written from the
 * same tree with the default settings of Jackson's YAML factory, which quotes every string, so
 * parsing it gives a tree equal to the JSON tree. Equal trees give equal bytes.
 */
final class DocumentWriter {

    /** The OpenAPI version every document declares. */
    static final String OPENAPI_VERSION = "3.1.1";

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private DocumentWriter() {}

    /**
     * Builds the {@code info} object of a document.
     *
     * @param info the configured {@code info} of the document
     * @return a new {@code info} object holding {@code title}, {@code description} when present, and
     *     {@code version}, in that order
     */
    static ObjectNode info(InfoConfig info) {
        ObjectNode infoNode = JSON.createObjectNode();
        infoNode.put("title", info.title());
        if (info.description() != null) {
            infoNode.put("description", info.description());
        }
        infoNode.put("version", info.version());
        return infoNode;
    }

    /**
     * Builds the {@code info} object of a document from the complete annotated {@code info}, in the
     * field order of the OpenAPI 3.1.1 Info Object: {@code title}, {@code summary}, {@code
     * description}, {@code termsOfService}, {@code contact} ({@code name}, {@code url}, {@code
     * email}, then its extensions), {@code license} ({@code name}, {@code identifier}, {@code url},
     * then its extensions), {@code version}, then the extensions. An unset member is not written;
     * each extension value is copied, so the returned tree shares no node with the argument.
     *
     * @param info the annotated {@code info} of the document
     * @return a new {@code info} object
     */
    static ObjectNode info(AnnotatedInfo info) {
        ObjectNode infoNode = JSON.createObjectNode();
        infoNode.put("title", info.title());
        putIfSet(infoNode, "summary", info.summary());
        putIfSet(infoNode, "description", info.description());
        putIfSet(infoNode, "termsOfService", info.termsOfService());
        AnnotatedInfo.ContactMembers contact = info.contact();
        if (contact != null) {
            ObjectNode contactNode = infoNode.putObject("contact");
            putIfSet(contactNode, "name", contact.name());
            putIfSet(contactNode, "url", contact.url());
            putIfSet(contactNode, "email", contact.email());
            putExtensions(contactNode, contact.extensions());
        }
        AnnotatedInfo.LicenseMembers license = info.license();
        if (license != null) {
            ObjectNode licenseNode = infoNode.putObject("license");
            putIfSet(licenseNode, "name", license.name());
            putIfSet(licenseNode, "identifier", license.identifier());
            putIfSet(licenseNode, "url", license.url());
            putExtensions(licenseNode, license.extensions());
        }
        infoNode.put("version", info.version());
        putExtensions(infoNode, info.extensions());
        return infoNode;
    }

    private static void putIfSet(ObjectNode node, String name, @Nullable String value) {
        if (value != null) {
            node.put(name, value);
        }
    }

    private static void putExtensions(ObjectNode node, Map<String, JsonNode> extensions) {
        extensions.forEach((name, value) -> node.set(name, value.deepCopy()));
    }

    /**
     * Copies a captured schema into a document tree node. The captured object is only read: the
     * returned tree is a new, independent copy, so changing it never reaches the captured object.
     *
     * @param captured the captured schema
     * @return a new tree holding the same members, in the same order
     * @throws IllegalStateException when the captured schema cannot be read as JSON
     */
    static ObjectNode tree(JsonObject captured) {
        try {
            return (ObjectNode) JSON.readTree(captured.encode());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A captured schema cannot be read as JSON", e);
        }
    }

    /**
     * Writes a document tree in both forms. The tree is not modified.
     *
     * @param root the root object of the document, members in the order they are written
     * @param snapshot the snapshot of the mount the document is assembled from
     * @return the document in both forms, with the entity tag of each
     * @throws IllegalStateException when the tree cannot be serialized
     */
    static PublishedDocument write(ObjectNode root, Snapshot snapshot) {
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
