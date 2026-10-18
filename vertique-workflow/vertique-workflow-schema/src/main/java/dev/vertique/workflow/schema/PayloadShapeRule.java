// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.schema;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The rule that a payload type is an object (or {@code void}), and the wording of the registration failure.
 *
 * <p>A document is object-shaped when the nodes that are conjoined with its root declare {@code type} exactly
 * {@code "object"} (or {@code ["object"]}) at least once and declare no other {@code type}. The conjoined nodes are
 * the root, every {@code allOf} branch, and the target of every local {@code $ref}, followed through at most
 * {@value #MAX_REFERENCE_HOPS} references. {@code anyOf} and {@code oneOf} are never entered: a root that states
 * {@code type: object} and also carries an {@code anyOf} presence rule is still an object.
 *
 * <p>The two document-object types are object-shaped by definition. The schema generator describes them as an
 * unconstrained schema, so they never reach the rule; the registration path substitutes a fixed document for them.
 */
final class PayloadShapeRule {

    /** The most {@code $ref} hops followed from the root. */
    static final int MAX_REFERENCE_HOPS = 16;

    private static final String TYPE = "type";
    private static final String OBJECT = "object";
    private static final String ALL_OF = "allOf";
    private static final String REF = "$ref";
    private static final String SELF_REFERENCE = "#";
    private static final String LOCAL_POINTER_PREFIX = "#/";
    private static final String NO_ROOT_TYPE = "none";

    private static final Set<String> OBJECT_BY_DEFINITION =
            Set.of(JsonObject.class.getName(), ObjectNode.class.getName());

    private PayloadShapeRule() {}

    /**
     * Where a payload type is declared, for the wording of a registration failure.
     *
     * @param definitionId the candidate definition id
     * @param definitionVersion the candidate definition version
     * @param stepId the human task step, or {@code null} for the start payload
     * @param decisionName the decision, or {@code null} for the start payload
     * @param typeName the payload type's binary name
     */
    record Context(
            String definitionId,
            long definitionVersion,
            @Nullable String stepId,
            @Nullable String decisionName,
            String typeName) {

        /**
         * The start of every failure message: the definition, the declaration site and the payload type.
         *
         * @return the message prefix, without a trailing space
         */
        String subject() {
            String site = stepId == null ? "start payload" : "step '" + stepId + "' decision '" + decisionName + "'";
            return "definition '" + definitionId + "' v" + definitionVersion + " " + site + " payload type '" + typeName
                    + "'";
        }
    }

    /**
     * Whether a payload type is object-shaped by definition and therefore bypasses generation.
     *
     * @param typeName the payload type's binary name
     * @return {@code true} for the JSON object type and the Jackson object node
     */
    static boolean isObjectByDefinition(String typeName) {
        return OBJECT_BY_DEFINITION.contains(typeName);
    }

    /**
     * The document substituted for a type that is object-shaped by definition.
     *
     * @return a fresh {@code {"type":"object"}}
     */
    static JsonObject objectByDefinitionDocument() {
        return new JsonObject().put(TYPE, OBJECT);
    }

    /**
     * Fails registration unless the document is object-shaped.
     *
     * @param document the canonical document of the payload type
     * @param context where the type is declared
     * @throws WorkflowDefinitionException when the document is not object-shaped
     */
    static void check(JsonObject document, Context context) {
        boolean objectDeclared = false;
        Object otherType = null;
        boolean otherDeclared = false;
        for (Map<?, ?> node : conjoinedNodes(document)) {
            if (!node.containsKey(TYPE)) {
                continue;
            }
            Object type = node.get(TYPE);
            if (isObjectType(type)) {
                objectDeclared = true;
            } else if (!otherDeclared) {
                otherDeclared = true;
                otherType = type;
            }
        }
        if (objectDeclared && !otherDeclared) {
            return;
        }
        throw new WorkflowDefinitionException(context.subject() + " is not object-shaped (root type "
                + (otherDeclared ? render(otherType) : NO_ROOT_TYPE) + "); wrap the value in a record");
    }

    private static boolean isObjectType(@Nullable Object type) {
        if (OBJECT.equals(type)) {
            return true;
        }
        if (type instanceof JsonArray array) {
            return array.size() == 1 && OBJECT.equals(array.getValue(0));
        }
        return type instanceof List<?> list && list.size() == 1 && OBJECT.equals(list.get(0));
    }

    private static String render(@Nullable Object type) {
        if (type instanceof JsonArray array) {
            return array.encode();
        }
        if (type instanceof JsonObject object) {
            return object.encode();
        }
        if (type instanceof List<?> list) {
            return new JsonArray(list).encode();
        }
        if (type instanceof Map<?, ?> map) {
            return new JsonObject(asStringKeyed(map)).encode();
        }
        return String.valueOf(type);
    }

    /**
     * Collects the nodes conjoined with the root in first-visit breadth-first order. A node is revisited only when it
     * is reached over fewer references than before, so a reference cycle terminates.
     */
    private static List<Map<?, ?>> conjoinedNodes(JsonObject document) {
        List<Map<?, ?>> nodes = new ArrayList<>();
        Map<Map<?, ?>, Integer> fewestHops = new IdentityHashMap<>();
        Deque<Visit> pending = new ArrayDeque<>();
        pending.add(new Visit(document.getMap(), 0));

        while (!pending.isEmpty()) {
            Visit visit = pending.poll();
            Integer seen = fewestHops.get(visit.node());
            if (seen != null && seen <= visit.hops()) {
                continue;
            }
            fewestHops.put(visit.node(), visit.hops());
            if (seen == null) {
                nodes.add(visit.node());
            }
            enqueueAllOfBranches(visit, pending);
            enqueueReference(document, visit, pending);
        }
        return nodes;
    }

    private static void enqueueAllOfBranches(Visit visit, Deque<Visit> pending) {
        if (visit.node().get(ALL_OF) instanceof Iterable<?> branches) {
            for (Object branch : branches) {
                Map<?, ?> node = asNode(branch);
                if (node != null) {
                    pending.add(new Visit(node, visit.hops()));
                }
            }
        }
    }

    private static void enqueueReference(JsonObject document, Visit visit, Deque<Visit> pending) {
        if (visit.hops() >= MAX_REFERENCE_HOPS || !(visit.node().get(REF) instanceof String reference)) {
            return;
        }
        if (!SELF_REFERENCE.equals(reference) && !reference.startsWith(LOCAL_POINTER_PREFIX)) {
            return;
        }
        Map<?, ?> target = resolve(document, reference);
        if (target != null) {
            pending.add(new Visit(target, visit.hops() + 1));
        }
    }

    /** Resolves a document-rooted pointer reference ({@code #} or {@code #/...}) to an object node, or null. */
    private static @Nullable Map<?, ?> resolve(JsonObject document, String reference) {
        if (SELF_REFERENCE.equals(reference)) {
            return document.getMap();
        }
        Object current = document.getMap();
        for (String segment : reference.substring(LOCAL_POINTER_PREFIX.length()).split("/", -1)) {
            String key = segment.replace("~1", "/").replace("~0", "~");
            if (asNode(current) instanceof Map<?, ?> map) {
                current = map.get(key);
            } else if (current instanceof Iterable<?> list && isIndex(key)) {
                current = element(list, Integer.parseInt(key));
            } else {
                return null;
            }
        }
        return asNode(current);
    }

    private static boolean isIndex(String key) {
        return !key.isEmpty() && key.length() < 10 && key.chars().allMatch(c -> c >= '0' && c <= '9');
    }

    private static @Nullable Object element(Iterable<?> list, int index) {
        int position = 0;
        for (Object item : list) {
            if (position++ == index) {
                return item;
            }
        }
        return null;
    }

    private static @Nullable Map<?, ?> asNode(@Nullable Object value) {
        if (value instanceof JsonObject object) {
            return object.getMap();
        }
        return value instanceof Map<?, ?> map ? map : null;
    }

    private static Map<String, Object> asStringKeyed(Map<?, ?> map) {
        Map<String, Object> copy = new LinkedHashMap<>();
        map.forEach((key, value) -> copy.put(String.valueOf(key), value));
        return copy;
    }

    /** A node and the number of references followed to reach it. */
    private record Visit(Map<?, ?> node, int hops) {}
}
