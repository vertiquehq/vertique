// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.assembly;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.vertique.json.schema.RedactionManifest;
import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.openapi.docs.schema.SchemaFragments;
import dev.vertique.rest.openapi.docs.schema.SchemaPositions;
import dev.vertique.rest.openapi.docs.schema.SchemaPublicationSubject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Removes the reserved-name assertions a verified redaction manifest lists from the document's own
 * copy of a request body schema.
 *
 * <p>Every pointer of the manifest is an RFC 6901 JSON Pointer into the schema as it was captured,
 * its root {@code $defs} included, so redaction runs before the schema is relocated into components.
 * Each pointer is read token by token, {@code ~1} unescaped to {@code /} and {@code ~0} to {@code ~}:
 * in an object a token names a member, in an array it is an index. Every pointer is resolved before
 * anything is removed; one that does not resolve, the empty pointer included, fails publication.
 * The value at each pointer, an object member or an array element, is then removed; the elements of
 * one array are removed in descending index order, so each index still names the element it was
 * resolved to. Nothing is matched by shape and nothing is simplified: an {@code allOf} left with one
 * element stays an {@code allOf}. A manifest without pointers leaves the schema unchanged.
 *
 * <p>The failure names the operation and the class of the bound schema source, never a pointer or
 * schema content. The captured schema is never changed.
 */
final class ReservedNameRedaction {

    private ReservedNameRedaction() {}

    /**
     * Removes every location a manifest lists from an owned body schema.
     *
     * @param subject the failure-message subject naming the application and its mount
     * @param operationId the runtime id of the operation
     * @param manifest the verified manifest of the captured body
     * @param schema the document's own copy of the captured body, changed in place
     * @param context the component's assembly inputs, naming the bound schema source
     * @param tally the disclosure tally of the document's assembly, told when anything was removed
     * @throws RestConfigurationException when a pointer does not resolve in the schema
     */
    static void redact(
            String subject,
            String operationId,
            RedactionManifest manifest,
            ObjectNode schema,
            AssemblyContext context,
            DisclosureTally tally) {
        List<ObjectRemoval> members = new ArrayList<>();
        Map<ArrayNode, SortedSet<Integer>> elements = new IdentityHashMap<>();
        for (String pointer : manifest.pointers()) {
            if (!resolve(schema, pointer, members, elements)) {
                throw new RestConfigurationException(subject + ": "
                        + SchemaPublicationSubject.body(operationId).refusalPhrase()
                        + " carries a redaction manifest that does not resolve in its schema "
                        + ManifestVerifier.sourcePhrase(context));
            }
        }
        for (ObjectRemoval removal : members) {
            removal.owner().remove(removal.member());
        }
        for (Map.Entry<ArrayNode, SortedSet<Integer>> entry : elements.entrySet()) {
            for (int index : entry.getValue()) {
                entry.getKey().remove(index);
            }
        }
        if (!members.isEmpty() || !elements.isEmpty()) {
            tally.reservedNameRemoved();
        }
    }

    /**
     * Resolves one pointer and records the removal it names.
     *
     * @return whether the pointer resolves
     */
    private static boolean resolve(
            ObjectNode schema,
            String pointer,
            List<ObjectRemoval> members,
            Map<ArrayNode, SortedSet<Integer>> elements) {
        if (pointer.isEmpty() || pointer.charAt(0) != '/') {
            return false;
        }
        String[] escaped = pointer.substring(1).split("/", -1);
        JsonNode parent = null;
        JsonNode node = schema;
        String token = null;
        for (String part : escaped) {
            token = SchemaFragments.unescape(part);
            if (token == null) {
                return false;
            }
            parent = node;
            if (node instanceof ObjectNode object) {
                node = object.get(token);
            } else if (node instanceof ArrayNode array) {
                int index = SchemaPositions.arrayIndex(token);
                node = index < 0 || index >= array.size() ? null : array.get(index);
            } else {
                node = null;
            }
            if (node == null) {
                return false;
            }
        }
        if (parent instanceof ObjectNode object) {
            members.add(new ObjectRemoval(object, token));
        } else {
            elements.computeIfAbsent((ArrayNode) parent, array -> new TreeSet<>(Comparator.reverseOrder()))
                    .add(SchemaPositions.arrayIndex(token));
        }
        return true;
    }

    /**
     * One object member to remove.
     *
     * @param owner the object holding the member
     * @param member the member name
     */
    private record ObjectRemoval(ObjectNode owner, String member) {}
}
