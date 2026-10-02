// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Builds the JSON Pointers of a served contract and follows its local references within the
 * document.
 *
 * <p>Pointers are RFC 6901 JSON Pointers without a leading {@code #}: every reference token is
 * escaped, {@code ~} as {@code ~0} and then {@code /} as {@code ~1}. A local reference is a string
 * {@code $ref} value that starts with {@code #/}; its fragment is read as {@link
 * SchemaFragments#pointerTokens} reads it. A chain of references is followed with a visited set, so
 * it always ends: at a node without a string {@code $ref}, at a reference that is not local (which
 * the literal reference check reports), at a reference that does not resolve, or at the reference
 * that closes a cycle. The last two are recorded as violations naming the pointer of the object
 * that carries the reference, never its value.
 */
final class ContractReferences {

    private final JsonNode root;
    private final Set<String> violations;

    /**
     * Creates the reference reader of one contract.
     *
     * @param root the contract's root object
     * @param violations the sink every unresolved or cyclic reference is recorded in
     */
    ContractReferences(JsonNode root, Set<String> violations) {
        this.root = root;
        this.violations = violations;
    }

    /**
     * One node of a reference chain.
     *
     * @param pointer the node's JSON Pointer
     * @param node the node
     */
    record Hop(String pointer, JsonNode node) {}

    /**
     * The nodes a reference chain visits, starting with the referencing node.
     *
     * @param hops the visited nodes in order; the last is the chain's end
     * @param complete whether the last node carries no string {@code $ref}, so the chain resolved
     */
    record Chain(List<Hop> hops, boolean complete) {

        /** Returns the last node of the chain. */
        Hop last() {
            return hops.get(hops.size() - 1);
        }
    }

    /**
     * Follows the local references that start at a node.
     *
     * @param pointer the starting node's JSON Pointer
     * @param node the starting node
     * @return every node visited, the starting node first
     */
    Chain follow(String pointer, JsonNode node) {
        List<Hop> hops = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        visited.add(pointer);
        hops.add(new Hop(pointer, node));
        String current = pointer;
        JsonNode at = node;
        while (at.isObject() && at.get("$ref") != null && at.get("$ref").isTextual()) {
            String reference = at.get("$ref").textValue();
            if (!reference.startsWith("#/")) {
                return new Chain(List.copyOf(hops), false);
            }
            String target = targetPointer(reference);
            JsonNode next = target == null ? null : resolve(target);
            if (next == null) {
                violations.add("the reference at " + display(current) + " does not resolve within the document");
                return new Chain(List.copyOf(hops), false);
            }
            if (!visited.add(target)) {
                violations.add("the reference at " + display(current) + " closes a reference cycle");
                return new Chain(List.copyOf(hops), false);
            }
            current = target;
            at = next;
            hops.add(new Hop(current, at));
        }
        return new Chain(List.copyOf(hops), true);
    }

    /**
     * Returns the JSON Pointer of a pointer's member or element.
     *
     * @param pointer the parent's JSON Pointer
     * @param token the unescaped member name or element index
     * @return the child's JSON Pointer
     */
    static String child(String pointer, String token) {
        return pointer + "/" + token.replace("~", "~0").replace("/", "~1");
    }

    /**
     * Renders a pointer, id, or key for a message: every control character becomes a Java-style
     * Unicode escape of four uppercase hexadecimal digits, and nothing is truncated.
     *
     * @param text the text to render
     * @return the rendered text
     */
    static String display(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isISOControl(c)) {
                out.append(String.format(Locale.ROOT, "\\u%04X", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** Returns the canonical JSON Pointer a local reference names, or {@code null} when it is malformed. */
    private static String targetPointer(String reference) {
        List<String> tokens = SchemaFragments.pointerTokens(reference);
        if (tokens == null) {
            return null;
        }
        String pointer = "";
        for (String token : tokens) {
            pointer = child(pointer, token);
        }
        return pointer;
    }

    /** Returns the node at a canonical JSON Pointer, or {@code null} when nothing is there. */
    private JsonNode resolve(String pointer) {
        JsonNode at = root.at(pointer);
        return at.isMissingNode() ? null : at;
    }
}
