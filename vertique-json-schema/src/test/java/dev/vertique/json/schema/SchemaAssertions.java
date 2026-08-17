// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Canonical-form, conjunctive-path, and golden-bytes helpers shared by {@link
 * AnnotationJsonSchemaGeneratorProofTest} and {@link SchemaFixtureMatrixTest}.
 *
 * <p>The conjunctive-path walk ({@link #conjunctiveClosure(JsonNode, JsonNode)}) delegates to the
 * main-source {@link ConjunctiveLocations#closure(JsonNode, JsonNode, java.util.Set)}: it is the
 * identical BFS expansion, so the test-side probe and the production walk cannot silently drift
 * apart.
 */
final class SchemaAssertions {

    /** Neutral mapper used to read and re-serialize documents inside the assertions. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SchemaAssertions() {}

    // --- Canonical-form assertions ---

    /**
     * Asserts that a canonical document is valid JSON, compact, and recursively key-sorted, and
     * returns its parsed form.
     *
     * @param canonical the canonical document text
     * @return the parsed document
     */
    static JsonNode assertCanonicalForm(String canonical) {
        assertNotNull(canonical, "the canonical document must not be null");
        JsonNode document = readTree(canonical);
        assertEquals(
                writeCompact(document),
                canonical,
                "the canonical document must be compact JSON with no re-serialization difference");
        assertKeysSorted(document, "#");
        return document;
    }

    private static JsonNode readTree(String canonical) {
        try {
            return MAPPER.readTree(canonical);
        } catch (IOException malformed) {
            return fail("the canonical document must be valid JSON", malformed);
        }
    }

    private static String writeCompact(JsonNode document) {
        try {
            return MAPPER.writeValueAsString(document);
        } catch (IOException unwritable) {
            return fail("the parsed document must be re-serializable", unwritable);
        }
    }

    /**
     * Asserts that every object's keys are in {@link String#compareTo(String)} order, recursively,
     * and that arrays are visited without reordering.
     *
     * @param node the node to check
     * @param path the JSON-pointer-ish path used in failure messages
     */
    private static void assertKeysSorted(JsonNode node, String path) {
        if (node.isObject()) {
            String previous = null;
            for (Map.Entry<String, JsonNode> entry : node.properties()) {
                if (previous != null && previous.compareTo(entry.getKey()) >= 0) {
                    fail("keys out of order at " + path + ": '" + previous + "' before '" + entry.getKey() + "'");
                }
                previous = entry.getKey();
                assertKeysSorted(entry.getValue(), path + "/" + entry.getKey());
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                assertKeysSorted(node.get(i), path + "/" + i);
            }
        }
    }

    // --- Conjunctive-path probe ---

    /**
     * Collects every schema node that conjunctively applies at {@code start}: the node itself, each
     * direct {@code allOf} branch, and each locally resolvable {@code $ref} target that is a schema
     * head. Delegates to {@link ConjunctiveLocations#closure(JsonNode, JsonNode, java.util.Set)},
     * seeded from {@link SchemaPositions#collectSchemaHeads(JsonNode)} — the identical two-pass
     * expansion the production walks fold over.
     *
     * @param document the whole document, used to resolve {@code $ref} pointers
     * @param start    the node whose conjunctive closure is wanted
     * @return the closure, in discovery order
     */
    static List<JsonNode> conjunctiveClosure(JsonNode document, JsonNode start) {
        return ConjunctiveLocations.closure(document, start, SchemaPositions.collectSchemaHeads(document));
    }

    /**
     * Collects every schema node that conjunctively applies to the named property, from every
     * conjunctive location of the document root that declares it.
     *
     * @param document the whole document
     * @param property the property name
     * @return the property's conjunctive closure
     */
    static List<JsonNode> propertyClosure(JsonNode document, String property) {
        List<JsonNode> collected = new ArrayList<>();
        for (JsonNode root : conjunctiveClosure(document, document)) {
            JsonNode properties = root.get("properties");
            if (properties == null || !properties.isObject()) {
                continue;
            }
            JsonNode declared = properties.get(property);
            if (declared != null) {
                collected.addAll(conjunctiveClosure(document, declared));
            }
        }
        assertFalse(collected.isEmpty(), "no schema node was found for property '" + property + "'");
        return collected;
    }

    /**
     * Collects the raw values a keyword takes across a set of conjunctive locations.
     *
     * @param nodes   the conjunctive locations
     * @param keyword the keyword to collect
     * @return every value found, in order
     */
    static List<JsonNode> keywordValues(List<JsonNode> nodes, String keyword) {
        List<JsonNode> values = new ArrayList<>();
        for (JsonNode node : nodes) {
            JsonNode value = node.get(keyword);
            if (value != null) {
                values.add(value);
            }
        }
        return values;
    }

    /**
     * Collects the textual values a keyword takes across a set of conjunctive locations, flattening
     * an array-valued occurrence (e.g. {@code "type": ["string", "null"]}).
     *
     * @param nodes   the conjunctive locations
     * @param keyword the keyword to collect
     * @return every textual value found
     */
    static List<String> textValues(List<JsonNode> nodes, String keyword) {
        List<String> values = new ArrayList<>();
        for (JsonNode node : nodes) {
            JsonNode value = node.get(keyword);
            if (value == null) {
                continue;
            }
            if (value.isArray()) {
                value.forEach(element -> {
                    if (element.isTextual()) {
                        values.add(element.textValue());
                    }
                });
            } else if (value.isTextual()) {
                values.add(value.textValue());
            }
        }
        return values;
    }

    /**
     * Recursively collects the textual values of every object member with the given key.
     *
     * @param node      the node to walk
     * @param key       the member key
     * @param collected the accumulator
     */
    static void collectMemberTexts(JsonNode node, String key, List<String> collected) {
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> entry : node.properties()) {
                if (entry.getKey().equals(key) && entry.getValue().isTextual()) {
                    collected.add(entry.getValue().textValue());
                }
                collectMemberTexts(entry.getValue(), key, collected);
            }
        } else if (node.isArray()) {
            node.forEach(element -> collectMemberTexts(element, key, collected));
        }
    }

    /**
     * Returns a node's textual value, or {@code null} when it is absent or not textual.
     *
     * @param node the node, possibly {@code null}
     * @return the textual value or {@code null}
     */
    static String text(JsonNode node) {
        return node != null && node.isTextual() ? node.textValue() : null;
    }

    // --- Golden bytes ---

    /**
     * Reads a committed golden document from the test classpath.
     *
     * <p>The resource path is absolute ({@code "/golden/..."}), so it resolves identically
     * regardless of which class's classloader performs the lookup.
     *
     * @param path the path under {@code /golden/} (e.g. {@code "matrix/ordinary-pojo.json"} or
     *             {@code "strict-input-amount.json"})
     * @return the golden document text, trailing newline stripped
     */
    static String golden(String path) {
        try (InputStream in = SchemaAssertions.class.getResourceAsStream("/golden/" + path)) {
            if (in == null) {
                return fail("golden document /golden/" + path
                        + " is not recorded yet — generate it, inspect it for contract correctness, then commit it");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            return fail("failed to read golden document /golden/" + path, e);
        }
    }
}
