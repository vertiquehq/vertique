// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.json.schema.Draft;
import io.vertx.json.schema.JsonSchema;
import io.vertx.json.schema.JsonSchemaOptions;
import io.vertx.json.schema.Validator;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Direct proofs over {@link AllOfFold#fold(JsonNode)} on hand-built raw documents — no generator, no
 * annotations. Each case builds a document with Jackson, folds it in place, and either asserts the
 * resulting shape, or (where the class Javadoc makes a "must be equivalent" claim) validates a small
 * instance set against both the pre-fold and the post-fold document with the real
 * {@code io.vertx.json.schema} validator, so a claimed equivalence is checked against the actual
 * Draft 2020-12 evaluator rather than against this test's own reading of the spec.
 *
 * <p>The fold is equivalence-preserving under the refusal conditions {@link AllOfFold}'s own class
 * Javadoc documents, and these tests pin the invariants that make that claim hold rather than merely
 * assert it: {@link #foldCarriesThePartsObjectTypeOntoTheTarget()} (and its order-independence twin,
 * {@link #foldResultIsIndependentOfPartOrder()}) pin that folding a plain part's implicit {@code
 * type: "object"} constraint onto a target that declares no {@code type} of its own carries that
 * constraint forward instead of dropping it, so a scalar or {@code null} instance the pre-fold
 * conjunction rejected is still rejected after folding. {@link
 * #targetTypeAdmittingNullRefusesToFold()} pins the sharper case: when the target's own {@code type}
 * already admits {@code null}, the fold is refused outright, since the target has no {@code type}
 * value that both keeps admitting whatever it already admitted and also excludes {@code null} the way
 * the part's implicit object-only constraint requires. {@link #everyLocalRefStillResolvesAfterFold()}
 * pins that folding never leaves a local {@code $ref} dangling or silently retargeted, whether it
 * points into an {@code allOf} array a fold would remove or renumber or into a target's own {@code
 * properties} a fold would rewrite. {@link
 * #noPlainPartSurvivesFoldEvenWhenItBecomesPlainOnlyAfterItsOwnNestedFold()} pins that the
 * parent-before-children traversal order within a single pass never stays the final word: a part that
 * becomes plain only once its own nested {@code allOf} is folded is still picked up by an ancestor's
 * target, on a later round of the repeated-pass fixed point.
 */
class AllOfFoldTest {

    private static final JsonSchemaOptions VALIDATOR_OPTIONS =
            new JsonSchemaOptions().setDraft(Draft.DRAFT202012).setBaseUri("https://vertique.local/");

    private static JsonNode read(String json) {
        return HardeningFixtures.read(json);
    }

    private static boolean valid(JsonNode schema, Object instance) {
        Validator validator = Validator.create(JsonSchema.of(new JsonObject(schema.toString())), VALIDATOR_OPTIONS);
        Boolean result = validator.validate(instance).getValid();
        return Boolean.TRUE.equals(result);
    }

    /**
     * One instance in a small equivalence matrix: its JSON-literal label (for failure messages), its
     * Java form as handed to the validator, and whether the class Javadoc's conjunction semantics say
     * it must be accepted.
     */
    private record Instance(String label, Object value, boolean expectedValid) {}

    private static void assertMatches(JsonNode schema, String schemaLabel, List<Instance> instances) {
        for (Instance instance : instances) {
            boolean actual = valid(schema, instance.value());
            assertEquals(
                    instance.expectedValid(),
                    actual,
                    schemaLabel + ": instance " + instance.label() + " expected valid=" + instance.expectedValid()
                            + " but the real validator says " + actual + "; schema: " + schema);
        }
    }

    // --- C-1: folding a type:"object" part must not drop the target's 'object' type ---

    @Test
    @DisplayName("folding a type:\"object\" part into a target with no type of its own must add"
            + " type:\"object\" to the target, not drop it")
    void foldCarriesThePartsObjectTypeOntoTheTarget() {
        JsonNode document = read("{\"properties\":{\"a\":{\"type\":\"string\"}},"
                + "\"allOf\":[{\"type\":\"object\",\"properties\":{\"b\":{\"type\":\"integer\"}}}]}");
        JsonNode unfolded = document.deepCopy();

        AllOfFold.fold(document);

        List<Instance> instances = List.of(
                new Instance("42", 42, false),
                new Instance("\"x\"", "x", false),
                new Instance("null", null, false),
                new Instance("[]", new JsonArray(), false),
                new Instance(
                        "{\"a\":\"s\",\"b\":1}", new JsonObject().put("a", "s").put("b", 1), true),
                new Instance("{\"b\":\"no\"}", new JsonObject().put("b", "no"), false));

        assertAll(
                () -> assertEquals(
                        "object",
                        document.path("type").asText(null),
                        "folding the allOf part's type:\"object\" into the target must publish"
                                + " type:\"object\" on the target; folded document: " + document),
                () -> assertMatches(unfolded, "pre-fold document (ground truth)", instances),
                () -> assertMatches(document, "post-fold document", instances));
    }

    @Test
    @DisplayName("folding two plain parts must produce the same type:\"object\" result and the same"
            + " acceptance regardless of which part is declared first")
    void foldResultIsIndependentOfPartOrder() {
        String partWithNoType = "{\"properties\":{\"a\":{}}}";
        String partWithType = "{\"type\":\"object\",\"properties\":{\"b\":{}}}";
        String anotherPartWithNoType = "{\"properties\":{\"c\":{}}}";

        JsonNode noTypeFirst = read("{\"allOf\":[" + partWithNoType + "," + partWithType + "]}");
        JsonNode typeFirst = read("{\"allOf\":[" + partWithType + "," + partWithNoType + "]}");
        // The type-absent-on-both-sides case: neither the target (no "type" of its own) nor either
        // part declares "type" at all, so unlike the two orderings above, folding must never
        // introduce type:"object" — the pre-fold conjunction never constrained the instance's own
        // type, and folding must not change that.
        JsonNode bothPartsNoType = read("{\"allOf\":[" + partWithNoType + "," + anotherPartWithNoType + "]}");
        JsonNode bothPartsNoTypeUnfolded = bothPartsNoType.deepCopy();

        AllOfFold.fold(noTypeFirst);
        AllOfFold.fold(typeFirst);
        AllOfFold.fold(bothPartsNoType);

        List<Instance> instances = List.of(
                new Instance("42", 42, false),
                new Instance("\"x\"", "x", false),
                new Instance("null", null, false),
                new Instance("[]", new JsonArray(), false),
                new Instance("{\"a\":1,\"b\":2}", new JsonObject().put("a", 1).put("b", 2), true));

        List<Instance> typeAbsentOnBothSidesInstances = List.of(
                new Instance("42", 42, true),
                new Instance("null", null, true),
                new Instance("{}", new JsonObject(), true));

        assertAll(
                () -> assertEquals(
                        "object",
                        noTypeFirst.path("type").asText(null),
                        "the no-type-first order must still carry type:\"object\" after folding; folded: "
                                + noTypeFirst),
                () -> assertEquals(
                        "object",
                        typeFirst.path("type").asText(null),
                        "the type-first order must still carry type:\"object\" after folding; folded: " + typeFirst),
                () -> assertMatches(noTypeFirst, "no-type-first fold order", instances),
                () -> assertMatches(typeFirst, "type-first fold order", instances),
                () -> assertNull(
                        bothPartsNoType.get("type"),
                        "when neither part declares type, folding must not invent type:\"object\" on the"
                                + " target; folded: " + bothPartsNoType),
                () -> assertMatches(
                        bothPartsNoTypeUnfolded,
                        "type-absent-on-both-sides, pre-fold document (ground truth)",
                        typeAbsentOnBothSidesInstances),
                () -> assertMatches(
                        bothPartsNoType,
                        "type-absent-on-both-sides, post-fold document",
                        typeAbsentOnBothSidesInstances),
                () -> {
                    for (Instance instance : instances) {
                        boolean noTypeFirstValid = valid(noTypeFirst, instance.value());
                        boolean typeFirstValid = valid(typeFirst, instance.value());
                        assertEquals(
                                typeFirstValid,
                                noTypeFirstValid,
                                "folding must be order-independent: instance " + instance.label()
                                        + " is valid=" + typeFirstValid + " under the type-first order but valid="
                                        + noTypeFirstValid + " under the no-type-first order; no-type-first: "
                                        + noTypeFirst + "; type-first: " + typeFirst);
                    }
                });
    }

    @Test
    @DisplayName("a target whose own type already admits null must refuse to fold a part that"
            + " implicitly requires type:\"object\", since folding would let null through")
    void targetTypeAdmittingNullRefusesToFold() {
        JsonNode document = read("{\"type\":[\"object\",\"null\"],"
                + "\"properties\":{\"a\":{\"type\":\"string\"}},"
                + "\"allOf\":[{\"type\":\"object\",\"properties\":{\"b\":{\"type\":\"integer\"}}}]}");
        JsonNode unfolded = document.deepCopy();

        AllOfFold.fold(document);

        assertAll(
                () -> assertFalse(
                        valid(unfolded, null),
                        "sanity: the ORIGINAL unfolded document must already reject null — the allOf"
                                + " part's type:\"object\" keyword applies unconditionally and rejects it"),
                () -> assertNotNull(
                        document.get("allOf"),
                        "a target whose own type admits null must not fold away a part that implicitly"
                                + " requires type:\"object\" (the part's type constraint would be lost,"
                                + " letting null satisfy the target when the original conjunction rejected"
                                + " it); folded document: " + document),
                () -> assertFalse(
                        valid(document, null),
                        "folding must never change whether null is accepted; the folded document must"
                                + " still reject null exactly as the pre-fold conjunction did: " + document));
    }

    // --- Rule coverage: green only if the implementation matches every documented rule ---

    @Test
    @DisplayName("a new key is added verbatim when the target names no additionalProperties")
    void newKeyAddedVerbatimWhenTargetHasNoAdditionalProperties() {
        JsonNode document = read("{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\"}},"
                + "\"allOf\":[{\"properties\":{\"b\":{\"type\":\"integer\"}}}]}");
        JsonNode unfolded = document.deepCopy();

        AllOfFold.fold(document);

        List<Instance> instances = List.of(
                new Instance(
                        "{\"a\":\"s\",\"b\":1}", new JsonObject().put("a", "s").put("b", 1), true),
                new Instance(
                        "{\"a\":\"s\",\"b\":\"no\"}",
                        new JsonObject().put("a", "s").put("b", "no"),
                        false),
                new Instance("{\"a\":1,\"b\":1}", new JsonObject().put("a", 1).put("b", 1), false));

        assertAll(
                () -> assertNull(document.get("allOf"), "a single fully-folded part must remove allOf: " + document),
                () -> assertEquals(
                        "integer",
                        document.at("/properties/b/type").asText(null),
                        "the new key must be added verbatim, with no allOf wrap: " + document),
                () -> assertMatches(unfolded, "pre-fold document (ground truth)", instances),
                () -> assertMatches(document, "post-fold document", instances));
    }

    @Test
    @DisplayName("a new key is added as allOf[additionalProperties, part] when the target's"
            + " additionalProperties is an object schema, and the merged constraint is enforced")
    void newKeyWrappedWithAdditionalPropertiesSchema() {
        JsonNode document = read("{\"type\":\"object\","
                + "\"properties\":{\"a\":{\"type\":\"string\"}},"
                + "\"additionalProperties\":{\"type\":\"integer\",\"minimum\":0},"
                + "\"allOf\":[{\"properties\":{\"p\":{\"type\":\"integer\",\"maximum\":100}}}]}");
        JsonNode unfolded = document.deepCopy();

        AllOfFold.fold(document);

        JsonNode pSchema = document.at("/properties/p");
        List<Instance> instances = List.of(
                new Instance("{\"p\":50}", new JsonObject().put("p", 50), true),
                new Instance("{\"p\":-5}", new JsonObject().put("p", -5), false),
                new Instance("{\"p\":200}", new JsonObject().put("p", 200), false));

        assertAll(
                () -> assertTrue(
                        pSchema.has("allOf"),
                        "a new key folded under an object-schema additionalProperties must be wrapped,"
                                + " not added verbatim: " + document),
                () -> {
                    ArrayNode branches = (ArrayNode) pSchema.get("allOf");
                    assertEquals(2, branches.size(), "exactly two branches: additionalProperties, then the part");
                    assertEquals(
                            read("{\"type\":\"integer\",\"minimum\":0}"),
                            branches.get(0),
                            "the target's own additionalProperties copied as the first branch");
                    assertEquals(
                            read("{\"type\":\"integer\",\"maximum\":100}"),
                            branches.get(1),
                            "the part's own schema for the key as the second branch");
                },
                () -> assertMatches(unfolded, "pre-fold document (ground truth)", instances),
                () -> assertMatches(document, "post-fold document", instances));
    }

    @Test
    @DisplayName("refuses to fold a new key when the target's additionalProperties is the literal false")
    void refusesNewKeyWhenAdditionalPropertiesFalse() {
        JsonNode document = read("{\"type\":\"object\",\"properties\":{\"a\":{}},"
                + "\"additionalProperties\":false,\"allOf\":[{\"properties\":{\"p\":{}}}]}");

        AllOfFold.fold(document);

        assertAll(
                () -> assertNotNull(
                        document.get("allOf"), "additionalProperties:false must refuse the fold: " + document),
                () -> assertFalse(document.at("/properties").has("p"), "the new key must not be added: " + document));
    }

    @Test
    @DisplayName("refuses to fold a new key when the target carries patternProperties")
    void refusesNewKeyWhenPatternProperties() {
        JsonNode document = read("{\"type\":\"object\",\"properties\":{\"a\":{}},"
                + "\"patternProperties\":{\"^x\":{\"type\":\"string\"}},\"allOf\":[{\"properties\":{\"p\":{}}}]}");

        AllOfFold.fold(document);

        assertAll(
                () -> assertNotNull(document.get("allOf"), "patternProperties must refuse the fold: " + document),
                () -> assertFalse(document.at("/properties").has("p"), "the new key must not be added: " + document));
    }

    @Test
    @DisplayName("refuses to fold a new key when the target carries unevaluatedProperties")
    void refusesNewKeyWhenUnevaluatedProperties() {
        JsonNode document = read("{\"type\":\"object\",\"properties\":{\"a\":{}},"
                + "\"unevaluatedProperties\":false,\"allOf\":[{\"properties\":{\"p\":{}}}]}");

        AllOfFold.fold(document);

        assertAll(
                () -> assertNotNull(document.get("allOf"), "unevaluatedProperties must refuse the fold: " + document),
                () -> assertFalse(document.at("/properties").has("p"), "the new key must not be added: " + document));
    }

    @Test
    @DisplayName("refuses to fold a new key when the target carries propertyNames and the part adds a key")
    void refusesNewKeyWhenPropertyNames() {
        JsonNode document = read("{\"type\":\"object\",\"properties\":{\"a\":{}},"
                + "\"propertyNames\":{\"enum\":[\"a\"]},\"allOf\":[{\"properties\":{\"p\":{}}}]}");

        AllOfFold.fold(document);

        assertAll(
                () -> assertNotNull(document.get("allOf"), "propertyNames must refuse the fold: " + document),
                () -> assertFalse(document.at("/properties").has("p"), "the new key must not be added: " + document));
    }

    @Test
    @DisplayName("a key both the target and the part declare, with unequal schemas, becomes allOf of both")
    void sharedKeyWithUnequalSchemasWrapsAllOf() {
        JsonNode document = read("{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\"}},"
                + "\"allOf\":[{\"properties\":{\"a\":{\"minLength\":2}}}]}");
        JsonNode unfolded = document.deepCopy();

        AllOfFold.fold(document);

        JsonNode aSchema = document.at("/properties/a");
        List<Instance> instances = List.of(
                new Instance("{\"a\":\"ab\"}", new JsonObject().put("a", "ab"), true),
                new Instance("{\"a\":\"a\"}", new JsonObject().put("a", "a"), false),
                new Instance("{\"a\":5}", new JsonObject().put("a", 5), false));

        assertAll(
                () -> assertNull(document.get("allOf"), "the single part must be fully absorbed: " + document),
                () -> assertTrue(aSchema.has("allOf"), "unequal shared-key schemas must wrap in allOf: " + document),
                () -> {
                    ArrayNode branches = (ArrayNode) aSchema.get("allOf");
                    assertEquals(2, branches.size());
                    assertEquals(read("{\"type\":\"string\"}"), branches.get(0));
                    assertEquals(read("{\"minLength\":2}"), branches.get(1));
                },
                () -> assertMatches(unfolded, "pre-fold document (ground truth)", instances),
                () -> assertMatches(document, "post-fold document", instances));
    }

    @Test
    @DisplayName("a key both the target and the part declare, with equal schemas, keeps one copy")
    void sharedKeyWithEqualSchemasKeepsOneCopy() {
        JsonNode document = read("{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\"}},"
                + "\"allOf\":[{\"properties\":{\"a\":{\"type\":\"string\"}}}]}");

        AllOfFold.fold(document);

        JsonNode aSchema = document.at("/properties/a");
        assertAll(
                () -> assertFalse(aSchema.has("allOf"), "equal shared-key schemas must not wrap in allOf: " + document),
                () -> assertEquals(read("{\"type\":\"string\"}"), aSchema));
    }

    @Test
    @DisplayName("required becomes the order-preserving, de-duplicated union of the target's and the part's")
    void requiredUnionPreservesOrderAndDeduplicates() {
        JsonNode document = read("{\"type\":\"object\","
                + "\"properties\":{\"a\":{},\"b\":{},\"c\":{}},"
                + "\"required\":[\"a\",\"b\"],"
                + "\"allOf\":[{\"required\":[\"b\",\"c\"]}]}");
        JsonNode unfolded = document.deepCopy();

        AllOfFold.fold(document);

        List<String> required = new ArrayList<>();
        document.get("required").forEach(name -> required.add(name.asText()));

        List<Instance> instances = List.of(
                new Instance(
                        "{\"a\":1,\"b\":2,\"c\":3}",
                        new JsonObject().put("a", 1).put("b", 2).put("c", 3),
                        true),
                new Instance(
                        "{\"a\":1,\"b\":2} (missing required c)",
                        new JsonObject().put("a", 1).put("b", 2),
                        false),
                new Instance(
                        "{\"b\":1,\"c\":1} (missing required a)",
                        new JsonObject().put("b", 1).put("c", 1),
                        false));

        assertAll(
                () -> assertEquals(List.of("a", "b", "c"), required, "document: " + document),
                () -> assertMatches(unfolded, "pre-fold document (ground truth)", instances),
                () -> assertMatches(document, "post-fold document", instances));
    }

    @Test
    @DisplayName("a part with empty properties folds to a no-op: the target's own properties are unchanged")
    void emptyPropertiesPartFoldsToNoOp() {
        JsonNode document = read("{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\"}},"
                + "\"allOf\":[{\"type\":\"object\",\"properties\":{}}]}");
        JsonNode propertiesBefore = document.get("properties").deepCopy();

        AllOfFold.fold(document);

        assertAll(
                () -> assertNull(document.get("allOf"), "an empty part must still be folded away: " + document),
                () -> assertEquals(
                        propertiesBefore,
                        document.get("properties"),
                        "an empty-properties part must contribute nothing: " + document));
    }

    @Test
    @DisplayName("a part whose own nested allOf can never fold (its inner member carries $ref) is"
            + " itself never folded into the outer target — unlike a part whose nested allOf merely"
            + " has not folded yet, which the post-order gap covers instead")
    void partWhoseNestedAllOfCannotFoldIsNeverFolded() {
        // The middle part's own allOf holds one member carrying $ref, which is structurally excluded
        // from folding (see #partWithRefIsNeverFolded) — not merely a nested allOf a later repeated
        // pass would eventually fold away, the case #noPlainPartSurvivesFoldEvenWhenItBecomesPlain
        // OnlyAfterItsOwnNestedFold covers. So the middle part can never become plain, no matter how
        // many repeated top-down passes fold() runs: this is a genuine, permanent invariant, not an
        // artifact of how many rounds the repeated-pass fixed point is given.
        JsonNode document = read("{\"type\":\"object\","
                + "\"properties\":{\"a\":{\"type\":\"string\"}},"
                + "\"allOf\":[{\"type\":\"object\",\"properties\":{\"b\":{\"type\":\"string\"}},"
                + "\"allOf\":[{\"$ref\":\"#/$defs/x\"}]}],"
                + "\"$defs\":{\"x\":{\"type\":\"object\",\"properties\":{\"c\":{\"type\":\"integer\"}},"
                + "\"required\":[\"c\"]}}}");
        JsonNode unfolded = document.deepCopy();
        JsonNode outerPropertiesBefore = document.get("properties").deepCopy();

        AllOfFold.fold(document);

        List<Instance> instances = List.of(
                new Instance(
                        "{\"a\":\"s\",\"b\":\"t\",\"c\":1}",
                        new JsonObject().put("a", "s").put("b", "t").put("c", 1),
                        true),
                new Instance(
                        "{\"a\":\"s\",\"b\":\"t\"} (missing required c)",
                        new JsonObject().put("a", "s").put("b", "t"),
                        false),
                new Instance(
                        "{\"a\":1,\"c\":2} (wrong type for a)",
                        new JsonObject().put("a", 1).put("c", 2),
                        false),
                new Instance("{\"c\":2}", new JsonObject().put("c", 2), true));

        assertAll(
                () -> assertNotNull(
                        document.get("allOf"),
                        "the middle part can never become plain (its own nested allOf can never fold),"
                                + " so it must never fold into the outer target: " + document),
                () -> assertEquals(
                        outerPropertiesBefore,
                        document.get("properties"),
                        "the outer target's own properties must be completely unchanged — 'b' (and 'c',"
                                + " reached only through the unfoldable $ref) must never leak up: " + document),
                () -> assertNotNull(
                        document.at("/allOf/0/allOf"),
                        "the middle part must stay an unfolded allOf member, keeping its own allOf"
                                + " (carrying the unfoldable $ref part) intact: " + document),
                () -> assertEquals(
                        1,
                        document.at("/allOf/0/allOf").size(),
                        "the middle part's own allOf must still hold exactly the unfoldable $ref part: " + document),
                () -> assertMatches(unfolded, "pre-fold document (ground truth)", instances),
                () -> assertMatches(document, "post-fold document", instances));
    }

    @Test
    @DisplayName("a part carrying $ref is never folded")
    void partWithRefIsNeverFolded() {
        JsonNode document =
                read("{\"type\":\"object\",\"properties\":{\"a\":{}},\"allOf\":[{\"$ref\":\"#/properties/a\"}]}");

        AllOfFold.fold(document);

        assertAll(
                () -> assertNotNull(document.get("allOf"), "a part with $ref must never be folded: " + document),
                () -> assertEquals(
                        "#/properties/a", document.at("/allOf/0/$ref").asText(null)));
    }

    @Test
    @DisplayName("a part carrying const is never folded")
    void partWithConstIsNeverFolded() {
        JsonNode document = read("{\"type\":\"object\",\"properties\":{\"a\":{}},"
                + "\"allOf\":[{\"type\":\"object\",\"const\":{\"a\":1}}]}");

        AllOfFold.fold(document);

        assertAll(
                () -> assertNotNull(document.get("allOf"), "a part with const must never be folded: " + document),
                () -> assertTrue(document.at("/allOf/0").has("const")));
    }

    // --- W-1: local $ref pointers into a folded allOf array must still resolve ---

    @Test
    @DisplayName("every local $ref in the document still resolves after folding")
    void everyLocalRefStillResolvesAfterFold() {
        JsonNode document = read("{\"type\":\"object\","
                + "\"properties\":{\"a\":{\"type\":\"string\"}},"
                + "\"allOf\":[{\"type\":\"object\",\"properties\":{\"b\":{\"type\":\"integer\"},"
                + "\"c\":{\"type\":\"string\"}}}],"
                + "\"$defs\":{\"x\":{\"$ref\":\"#/allOf/0/properties/b\"}}}");
        JsonNode referencedBefore = document.at("/allOf/0/properties/b").deepCopy();

        AllOfFold.fold(document);

        List<String> unresolved = new ArrayList<>();
        collectUnresolvedLocalRefs(document, document, unresolved);
        assertAll(
                () -> assertTrue(
                        unresolved.isEmpty(),
                        "folding rewrote or removed the allOf array without updating local $ref pointers"
                                + " into it; unresolved: " + unresolved + "; document: " + document),
                () -> assertEquals(
                        referencedBefore,
                        document.at("/allOf/0/properties/b"),
                        "the referenced node itself must still resolve to the exact same content, not"
                                + " merely to some node — folding must not merely keep the pointer valid"
                                + " while silently changing what it points at: " + document));
    }

    private static void collectUnresolvedLocalRefs(JsonNode node, JsonNode root, List<String> unresolved) {
        if (node.isObject()) {
            JsonNode ref = node.get("$ref");
            if (ref != null && ref.isTextual() && ref.asText().startsWith("#/")) {
                String pointer = ref.asText().substring(1);
                if (root.at(pointer).isMissingNode()) {
                    unresolved.add(ref.asText());
                }
            }
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                collectUnresolvedLocalRefs(field.getValue(), root, unresolved);
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                collectUnresolvedLocalRefs(child, root, unresolved);
            }
        }
    }

    // --- W-4: a $ref pointing into the target's own properties must refuse the fold ---

    @Test
    @DisplayName("a $ref pointing into the target's own properties refuses the fold, since folding"
            + " would rewrite exactly the node that pointer resolves to")
    void refIntoTargetPropertiesRefusesTheFold() {
        JsonNode document = read("{\"properties\":{\"a\":{\"type\":\"string\"},"
                + "\"z\":{\"$ref\":\"#/properties/a\"}},"
                + "\"allOf\":[{\"properties\":{\"a\":{\"minLength\":2}}}]}");
        JsonNode unfolded = document.deepCopy();

        AllOfFold.fold(document);

        List<Instance> instances = List.of(
                new Instance(
                        "{\"a\":\"xy\",\"z\":\"q\"}",
                        new JsonObject().put("a", "xy").put("z", "q"),
                        true),
                new Instance(
                        "{\"a\":\"x\",\"z\":\"q\"}",
                        new JsonObject().put("a", "x").put("z", "q"),
                        false));

        assertAll(
                () -> assertNotNull(
                        document.get("allOf"),
                        "a $ref into the target's own properties (here #/properties/a, the exact key"
                                + " the part would fold a wrapped allOf into) must refuse the whole fold —"
                                + " see the targetPath + \"/properties\" guard in AllOfFold.foldAt; folded"
                                + " document: " + document),
                () -> assertMatches(unfolded, "pre-fold document (ground truth)", instances),
                () -> assertMatches(document, "post-fold document", instances));
    }

    // --- W-2: a part that becomes plain only after its own nested allOf is folded ---

    @Test
    @DisplayName("no plain part survives folding, even one that becomes plain only after its own"
            + " nested allOf is folded")
    void noPlainPartSurvivesFoldEvenWhenItBecomesPlainOnlyAfterItsOwnNestedFold() {
        JsonNode document = read("{\"type\":\"object\","
                + "\"properties\":{\"h\":{\"type\":\"string\"}},"
                + "\"allOf\":[{\"type\":\"object\",\"properties\":{\"c\":{}},"
                + "\"allOf\":[{\"type\":\"object\",\"properties\":{\"d\":{}}}]}]}");
        JsonNode unfolded = document.deepCopy();

        AllOfFold.fold(document);

        List<String> remainingPlainParts = new ArrayList<>();
        findRemainingPlainParts(document, "#", remainingPlainParts);

        List<Instance> instances = List.of(
                new Instance(
                        "{\"h\":\"s\",\"c\":1,\"d\":2}",
                        new JsonObject().put("h", "s").put("c", 1).put("d", 2),
                        true),
                new Instance("42", 42, false),
                new Instance("null", null, false));

        assertAll(
                () -> assertTrue(
                        remainingPlainParts.isEmpty(),
                        "a part that becomes plain only after its own nested allOf is folded must still"
                                + " end up merged into an ancestor target, not left sitting in an allOf"
                                + " array; found: " + remainingPlainParts + "; document: " + document),
                () -> assertMatches(unfolded, "pre-fold document (ground truth)", instances),
                () -> assertMatches(document, "post-fold document", instances));
    }

    private static final Set<String> PLAIN_PART_KEYS = Set.of("type", "properties", "required", "title", "description");

    private static void findRemainingPlainParts(JsonNode node, String path, List<String> offendingPaths) {
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
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                findRemainingPlainParts(field.getValue(), path + "/" + field.getKey(), offendingPaths);
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                findRemainingPlainParts(node.get(i), path + "[" + i + "]", offendingPaths);
            }
        }
    }

    private static boolean isPlainObjectSchema(JsonNode part) {
        if (part == null || !part.isObject()) {
            return false;
        }
        Iterator<String> names = part.fieldNames();
        while (names.hasNext()) {
            if (!PLAIN_PART_KEYS.contains(names.next())) {
                return false;
            }
        }
        return part.has("properties") || "object".equals(part.path("type").asText(null));
    }
}
