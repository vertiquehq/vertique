// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import static dev.vertique.json.schema.SchemaAssertions.assertCanonicalForm;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.databind.JsonNode;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.json.DefaultJsonMapperProfileRegistry;
import jakarta.validation.constraints.Size;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * gap-3c89fb8e: when victools cannot consolidate an {@code allOf} whose parts both declare the same
 * property with different schemas — a polymorphic subtype whose discriminator {@code kind} is also a
 * declared property (HJ03's {@code J03AnySub} / {@code J03PlainSub}), or a {@code @JsonUnwrapped}
 * child whose definition provider leaves an {@code allOf} part with the child's own properties (UW2's
 * {@code UW2UnwrappedAlias}) — {@link AllOfFold} folds the input document's {@code allOf} into one
 * flat {@code properties} set instead of leaving it unconsolidated.
 *
 * <p>Each surviving {@code allOf} part that carries only {@code type}/{@code properties}/
 * {@code required}/{@code title}/{@code description} is exactly the shape {@code
 * McpSchemaHardener.closeRecursively}'s non-root closure guard targets (non-empty {@code
 * properties}, no {@code $ref}, no declared {@code additionalProperties} of its own — see
 * {@code vertique-mcp/vertique-mcp-server/.../runtime/McpSchemaHardener.java}, {@code
 * closeRecursively} around line 80): once the hardener closes that part alone with {@code
 * additionalProperties: false}, a property published only on a sibling part becomes an "additional
 * property" and a valid body is rejected at the tool-input boundary. Consolidating the {@code allOf}
 * into one flat {@code properties} set — kind present once — removes the shape the closure guard
 * targets, which is what this test demands.
 *
 * <p>Fixtures are copied verbatim (identifiers kept) from {@code evidence/proof-harness
 * /deser-validation-bv/src/probe/Shapes.java} under {@code docs/specs
 * /rest-021-deserializer-driven-schema-description/} (HJ03, J03Base, J03AnySub, J03PlainSub, and the
 * UW2 unwrapped shape).
 */
class UnmergedAllOfFoldTest {

    private static JsonMapperProfile vertiqueProfile() {
        return new DefaultJsonMapperProfileRegistry(Set.of()).profile(JsonProfileId.of("vertique"));
    }

    private static JsonNode inputDocument(Type type) {
        return assertCanonicalForm(
                AnnotationJsonSchemaGenerator.forInputProfile(vertiqueProfile()).generateCanonical(type));
    }

    @Test
    @DisplayName("HJ03: the polymorphic subtype publishes one flat properties set, kind present once,"
            + " with no unmerged allOf part")
    void hj03SubtypesPublishOneFlatPropertiesSet() {
        JsonNode document = inputDocument(HJ03.class);

        assertNoUnmergedAllOfPart(document, "HJ03");
    }

    @Test
    @DisplayName("UW2: the unwrapped child publishes one flat properties set with no unmerged allOf part")
    void uw2UnwrappedChildPublishesOneFlatPropertiesSet() {
        JsonNode document = inputDocument(UW2UnwrappedAlias.class);

        assertNoUnmergedAllOfPart(document, "UW2UnwrappedAlias");
    }

    // --- The whole-tree walk ---

    private static final Set<String> PLAIN_OBJECT_SCHEMA_KEYS =
            Set.of("type", "properties", "required", "title", "description");

    /**
     * Walks every node of {@code document} and fails at the first {@code allOf} array carrying a
     * "plain" object-schema part: a part whose only keys are drawn from {@link
     * #PLAIN_OBJECT_SCHEMA_KEYS}. That shape is precisely the one {@code McpSchemaHardener}'s
     * non-root closure guard targets, so its presence in an {@code allOf} means the fold this task
     * requires has not happened yet.
     *
     * @param document the generated input-direction document to walk
     * @param subject the fixture name, for the failure message
     */
    private static void assertNoUnmergedAllOfPart(JsonNode document, String subject) {
        List<String> offendingPaths = new ArrayList<>();
        walk(document, "#", offendingPaths);
        assertTrue(
                offendingPaths.isEmpty(),
                subject + ": the document must fold every allOf into one flat properties set (kind"
                        + " present once); found an unmerged plain allOf part at: " + offendingPaths
                        + " in document: " + document);
    }

    private static void walk(JsonNode node, String path, List<String> offendingPaths) {
        if (node == null || node.isMissingNode()) {
            return;
        }
        if (node.isObject()) {
            JsonNode allOf = node.get("allOf");
            if (allOf != null && allOf.isArray()) {
                for (int i = 0; i < allOf.size(); i++) {
                    JsonNode part = allOf.get(i);
                    if (isPlainObjectSchema(part)) {
                        offendingPaths.add(path + "/allOf[" + i + "]");
                    }
                }
            }
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                walk(field.getValue(), path + "/" + field.getKey(), offendingPaths);
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                walk(node.get(i), path + "[" + i + "]", offendingPaths);
            }
        }
    }

    private static boolean isPlainObjectSchema(JsonNode part) {
        if (part == null || !part.isObject()) {
            return false;
        }
        Iterator<String> names = part.fieldNames();
        while (names.hasNext()) {
            if (!PLAIN_OBJECT_SCHEMA_KEYS.contains(names.next())) {
                return false;
            }
        }
        return part.has("properties") || "object".equals(part.path("type").asText(null));
    }

    // --- Fixtures, copied verbatim from Shapes.java ---

    /** HJ03: any-setter on a polymorphic subtype. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "kind")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = J03AnySub.class, name = "any"),
        @JsonSubTypes.Type(value = J03PlainSub.class, name = "plain")
    })
    abstract static class J03Base {
        public String kind;
    }

    static class J03AnySub extends J03Base {
        public String name;

        @JsonAnySetter
        private Map<String, String> extras = new HashMap<>();
    }

    static class J03PlainSub extends J03Base {
        public String other;
    }

    static class HJ03 {
        public String label;
        public J03Base value;
    }

    /** UW2: a {@code @JsonUnwrapped} child whose member carries an alias. */
    static class UW2AliasChild {
        @JsonAlias("nm")
        @Size(max = 3)
        public String name;
    }

    static class UW2UnwrappedAlias {
        public String label;

        @JsonUnwrapped
        public UW2AliasChild inner;

        @JsonAnySetter
        private Map<String, Object> extras = new HashMap<>();
    }
}
