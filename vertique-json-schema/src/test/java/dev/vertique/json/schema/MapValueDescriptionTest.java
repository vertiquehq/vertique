// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import static dev.vertique.json.schema.SchemaAssertions.assertCanonicalForm;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.core.json.JsonSchemaFragment;
import dev.vertique.core.json.JsonSchemaTypeOverride;
import dev.vertique.json.DefaultJsonMapperProfileRegistry;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * rest-023 T003 ({@code D001}, TP-001, TP-002, TP-005, TP-007, TP-008). A {@code Map<K,V>} property,
 * parameter, extras value, collection item, or nested map is described, on the input direction only,
 * with {@code V}'s own schema — including a type-use constraint on {@code V} and {@code V}'s own
 * declared constraints, both through the shared value-position renderer (T001) — as the map's {@code
 * additionalProperties} schema. A {@code V} of {@code JsonNode}, {@code TreeNode}, or another
 * unconstrained value type stays unconstrained; an {@code Optional<T>}-valued map entry inherits T002's
 * own null-and-constraint rendering.
 *
 * <p>Every document is generated under the registry's built-in {@code vertique} profile, matching the
 * package's other unit proofs, except where a test declares its own profile (TP-005's override
 * companion).
 */
class MapValueDescriptionTest {

    // --- Generation helpers ---

    private static JsonMapperProfile profile() {
        return new DefaultJsonMapperProfileRegistry(Set.of()).profile(JsonProfileId.of("vertique"));
    }

    private static JsonNode inputDocument(Class<?> type) {
        return assertCanonicalForm(
                AnnotationJsonSchemaGenerator.forInputProfile(profile()).generateCanonical(type));
    }

    private static JsonNode inputDocument(Class<?> type, JsonMapperProfile customProfile) {
        return assertCanonicalForm(
                AnnotationJsonSchemaGenerator.forInputProfile(customProfile).generateCanonical(type));
    }

    private static JsonNode outputDocument(Class<?> type) {
        return assertCanonicalForm(
                AnnotationJsonSchemaGenerator.forOutputProfile(profile()).generateCanonical(type));
    }

    /**
     * Follows a local {@code $ref} into the document's own definitions, so a proof reads the same
     * schema whether the generator inlined the value type or shared it under {@code $defs}.
     *
     * @param document the whole document, which owns the definitions
     * @param node     the node that may be a reference
     * @return the referenced schema, or {@code node} when it is not a local reference
     */
    private static JsonNode resolve(JsonNode document, JsonNode node) {
        JsonNode reference = node.path("$ref");
        if (!reference.isTextual() || !reference.asText().startsWith("#/")) {
            return node;
        }
        return document.at(reference.asText().substring(1));
    }

    /**
     * Returns a named property's own schema, resolved through a {@code $ref} when present.
     *
     * @param document the whole document
     * @param name     the property name
     * @return the resolved property schema
     */
    private static JsonNode property(JsonNode document, String name) {
        return resolve(document, document.path("properties").path(name));
    }

    /**
     * Returns a map-typed schema's own {@code additionalProperties} value schema, resolved through a
     * {@code $ref} when present, failing when the schema declares none at all.
     *
     * @param document the whole document, printed on failure
     * @param mapSchema the map's own (already-resolved) schema
     * @param subject   the fixture name and position, for the failure message
     * @return the resolved {@code additionalProperties} schema
     */
    private static JsonNode additionalProperties(JsonNode document, JsonNode mapSchema, String subject) {
        assertTrue(
                mapSchema.has("additionalProperties"),
                subject + " must describe its map value through additionalProperties; document: " + document);
        return resolve(document, mapSchema.get("additionalProperties"));
    }

    // --- TP-001: a Map<K,V> property describes V's own schema, including a type-use constraint ---

    @Test
    @DisplayName("A Map<K,V> property describes V's own schema, including a type-use constraint, as"
            + " additionalProperties")
    void mapValueDescribesTheValueTypesOwnSchemaIncludingATypeUseConstraint() {
        JsonNode document = inputDocument(S2bTypeUseHolder.class);
        JsonNode tags = property(document, "tags");
        JsonNode additional = additionalProperties(document, tags, "S2bTypeUseHolder.tags");

        assertEquals(
                "{\"maxLength\":3,\"type\":\"string\"}",
                additional.toString(),
                "tags.additionalProperties must describe the type-use-constrained String schema, not"
                        + " {\"type\":\"object\"}; document: " + document);
    }

    @Test
    @DisplayName("Sensitivity proof: the output-direction generator's own rendering is unaffected")
    void outputDirectionMapValueRenderingIsUnaffectedByTheOverlay() {
        JsonNode document = outputDocument(S2bTypeUseHolder.class);
        JsonNode tags = property(document, "tags");

        assertFalse(
                tags.has("additionalProperties"),
                "the output-direction generator must not route a map position through this task's own"
                        + " input-only renderer extension; document: " + document);
    }

    @Test
    @DisplayName("N1: the type-use overlay is applied per position, not baked into a shared $defs entry")
    void typeUseOverlayIsAppliedPerPositionNotIntoASharedDefinition() {
        JsonNode document = inputDocument(TypeUseLeakageHolder.class);

        JsonNode constrained =
                additionalProperties(document, property(document, "constrained"), "TypeUseLeakageHolder.constrained");
        JsonNode plain = additionalProperties(document, property(document, "plain"), "TypeUseLeakageHolder.plain");

        assertEquals(
                3,
                constrained.path("maxLength").asInt(-1),
                "the constrained member's own additionalProperties must carry maxLength: 3, proving the"
                        + " overlay is not baked into a shared type-scope definition; document: " + document);
        assertFalse(
                plain.has("maxLength"),
                "an unconstrained sibling member of the same underlying Map<String, String> type must not"
                        + " inherit the constrained member's own overlay; document: " + document);
    }

    @Test
    @DisplayName("A Map subclass's supertype type-use constraint is rendered as a type and as a property")
    void mapSubclassSupertypeTypeUseConstraintIsRenderedAsTypeAndAsProperty() {
        JsonNode asType = inputDocument(Tags.class);
        JsonNode asProperty = inputDocument(TagsHolder.class);

        assertEquals(
                3,
                additionalProperties(asType, asType, "Tags (as type)")
                        .path("maxLength")
                        .asInt(-1),
                "Tags's own supertype type-use constraint must render maxLength: 3 when Tags is the root"
                        + " type; document: " + asType);
        assertEquals(
                3,
                additionalProperties(asProperty, property(asProperty, "tags"), "TagsHolder.tags")
                        .path("maxLength")
                        .asInt(-1),
                "Tags's own supertype type-use constraint must render maxLength: 3 at a named property"
                        + " position too; document: " + asProperty);
    }

    @Test
    @DisplayName("A further Map subclass with no type argument of its own follows the supertype chain")
    void mapSubclassSupertypeChainFollowsToTheFurthestSupertype() {
        JsonNode document = inputDocument(A.class);

        assertEquals(
                3,
                additionalProperties(document, document, "A (as type)")
                        .path("maxLength")
                        .asInt(-1),
                "A declares no type argument of its own: the overlay source must follow the supertype"
                        + " chain to Tags's own getAnnotatedSuperclass(), not stop one level short;"
                        + " document: " + document);
    }

    // --- TP-002: a Map<K, Bean> and a Map<K, Optional<Bean>> property describe the value type's schema ---

    @Test
    @DisplayName("A Map<K, Bean> property describes the bean value type's own schema as additionalProperties")
    void mapValueDescribesABeanValueTypesOwnSchemaAsAdditionalProperties() {
        JsonNode document = inputDocument(S2aBeanHolder.class);
        JsonNode labels = additionalProperties(document, property(document, "labels"), "S2aBeanHolder.labels");

        assertEquals(
                3,
                labels.path("properties").path("name").path("maxLength").asInt(-1),
                "labels.additionalProperties must describe Plain's own schema, including name's own"
                        + " maxLength: 3; document: " + document);
    }

    @Test
    @DisplayName("A Map<K, Optional<Bean>> property describes the bean value type's schema marked nullable")
    void mapValueDescribesAnOptionalBeanValueTypesOwnSchemaMarkedNullable() {
        JsonNode document = inputDocument(S2dOptionalBeanHolder.class);
        JsonNode opts = property(document, "opts");

        assertTrue(
                opts.has("additionalProperties"),
                "opts must describe its Optional<Plain> value through additionalProperties; document: " + document);
        JsonNode additional = resolve(document, opts.get("additionalProperties"));
        JsonNode nonNull = nonNullBranch(document, additional);

        assertAll(
                () -> assertTrue(
                        isMarkedNullable(additional),
                        "opts.additionalProperties must be marked nullable, through T002's own renderer"
                                + " step; document: " + document),
                () -> assertEquals(
                        3,
                        nonNull.path("properties")
                                .path("name")
                                .path("maxLength")
                                .asInt(-1),
                        "the non-null branch must still describe Plain's own maxLength: 3; document: " + document));
    }

    @Test
    @DisplayName("Sensitivity proof: a Map<K, JsonNode> property stays unconstrained")
    void mapValueOfJsonNodeStaysUnconstrained() {
        JsonNode document = inputDocument(S2cJsonNodeHolder.class);
        JsonNode raw = property(document, "raw");

        assertEquals(
                "{\"type\":\"object\"}",
                raw.toString(),
                "a Map<String, JsonNode> property must stay open — no additionalProperties member at all"
                        + " — this control must be green both before and after this task's own fix;"
                        + " document: " + document);
    }

    // --- TP-005: a map value whose type would itself be refused at a named position refuses at startup ---

    @Test
    @DisplayName("A map value of a refused type fails router construction, naming the override remedy")
    void mapValueOfARefusedTypeFailsRouterConstructionWithTheOverrideRemedy() {
        JsonSchemaGenerationException failure = assertThrows(
                JsonSchemaGenerationException.class, () -> AnnotationJsonSchemaGenerator.forInputProfile(profile())
                        .generateCanonical(RefusedMapValueHolder.class));

        assertTrue(
                failure.getMessage().contains("whose wire shape the generator cannot describe"),
                () -> "unexpected message: " + failure.getMessage());
        assertTrue(
                failure.getMessage().contains("JsonSchemaTypeOverride"),
                () -> "the message must name the remedy, JsonSchemaTypeOverride; was: " + failure.getMessage());
    }

    @Test
    @DisplayName("Sensitivity proof: an override-declared map value type succeeds")
    void mapValueOfAnOverrideDeclaredTypeSucceeds() {
        JsonMapperProfile withOverride = overrideProfile(
                RefusedValue.class, JsonSchemaFragment.parse("{\"type\":\"string\",\"format\":\"marker-t003-tp005\"}"));

        JsonNode document = inputDocument(RefusedMapValueHolder.class, withOverride);
        JsonNode additional =
                additionalProperties(document, property(document, "values"), "RefusedMapValueHolder.values");

        assertEquals(
                "marker-t003-tp005",
                additional.path("format").asText(null),
                "an override declared for the refused value type must apply at the map-value position,"
                        + " proving the refusal is specific to the undeclared type, not every map value;"
                        + " document: " + document);
    }

    // --- TP-007: a member-level additionalProperties annotation on a Map-typed member is ignored ---

    @Test
    @DisplayName("A member-level or getter-level additionalProperties annotation on a Map-typed member is"
            + " ignored, with a WARN log line only when the annotation is present")
    void memberLevelAdditionalPropertiesOnAMapTypedMemberIsIgnoredWithAWarnLog() {
        assertAll(
                () -> assertIgnoredWithWarning(MemberLevelTrue.class, "MemberLevelTrue", true),
                () -> assertIgnoredWithWarning(MemberLevelFalse.class, "MemberLevelFalse", true),
                () -> assertIgnoredWithWarning(GetterLevelTrue.class, "GetterLevelTrue", true),
                () -> assertIgnoredWithWarning(GetterLevelFalse.class, "GetterLevelFalse", true),
                () -> assertIgnoredWithWarningNested(NestedHolderOfMemberLevelTrue.class, "MemberLevelTrue-nested"),
                () -> assertIgnoredWithWarningNested(NestedHolderOfMemberLevelFalse.class, "MemberLevelFalse-nested"),
                () -> assertIgnoredWithWarningNested(NestedHolderOfGetterLevelTrue.class, "GetterLevelTrue-nested"),
                () -> assertIgnoredWithWarningNested(NestedHolderOfGetterLevelFalse.class, "GetterLevelFalse-nested"),
                () -> assertIgnoredWithWarning(NoAnnotationControl.class, "NoAnnotationControl", false));
    }

    private static void assertIgnoredWithWarning(Class<?> type, String subject, boolean expectWarning) {
        List<String> warnings = new ArrayList<>();
        JsonNode document = captureWarnings(warnings, () -> inputDocument(type));
        JsonNode attributes = additionalProperties(document, property(document, "attributes"), subject + ".attributes");

        assertEquals(
                "{\"type\":\"string\"}",
                attributes.toString(),
                subject + " must render attributes.additionalProperties identically to the no-annotation"
                        + " control, regardless of TRUE/FALSE; document: " + document);
        assertWarningPresence(warnings, subject, expectWarning);
    }

    private static void assertIgnoredWithWarningNested(Class<?> type, String subject) {
        List<String> warnings = new ArrayList<>();
        JsonNode document = captureWarnings(warnings, () -> inputDocument(type));
        JsonNode resolvedNested = property(document, "nested");
        JsonNode nestedAttributes = additionalProperties(
                document,
                resolve(document, resolvedNested.path("properties").path("attributes")),
                subject + ".nested.attributes");

        assertEquals(
                "{\"type\":\"string\"}",
                nestedAttributes.toString(),
                subject + " (nested) must render attributes.additionalProperties identically to the"
                        + " no-annotation control; document: " + document);
        assertWarningPresence(warnings, subject, true);
    }

    private static void assertWarningPresence(List<String> warnings, String subject, boolean expectWarning) {
        if (expectWarning) {
            assertTrue(
                    warnings.stream()
                            .anyMatch(message -> message.contains("attributes")
                                    && message.contains("has no effect on the generated input schema")),
                    subject + " must emit a WARNING naming the member 'attributes' and stating the"
                            + " annotation has no effect on the generated input schema; captured: " + warnings);
        } else {
            assertTrue(
                    warnings.isEmpty(),
                    subject + " (no annotation) must emit no WARNING at all; captured: " + warnings);
        }
    }

    @Test
    @DisplayName("Sensitivity proof: a class-level FALSE on a bean value type still closes it")
    void classLevelFalseOnABeanValueTypeStillClosesIt() {
        JsonNode document = inputDocument(ClosedBeanHolder.class);
        JsonNode detail = property(document, "detail");
        JsonNode resolvedDetail = resolve(document, detail);

        assertTrue(
                resolvedDetail.path("additionalProperties").isBoolean()
                        && !resolvedDetail.path("additionalProperties").asBoolean(),
                "a class-level @Schema(additionalProperties = FALSE) on a bean (non-Map) value type must"
                        + " still close it — this task's own ignore rule is scoped to a Map-typed member or"
                        + " getter specifically; document: " + document);
    }

    // --- TP-008: a nested map inside an any-setter's own extras value is described (N9) ---

    @Test
    @DisplayName("A nested map inside an any-setter's own extras value is described through the shared renderer")
    void nestedMapInsideAnySetterExtrasIsDescribed() {
        JsonNode document = inputDocument(NestedMapExtrasHolder.class);

        assertTrue(
                document.has("additionalProperties"),
                "the outer extras must describe additionalProperties;" + " document: " + document);
        JsonNode outer = resolve(document, document.get("additionalProperties"));
        JsonNode inner = additionalProperties(document, outer, "NestedMapExtrasHolder (outer extras)");

        assertEquals(
                3,
                inner.path("maxLength").asInt(-1),
                "the inner map's own value schema must carry maxLength: 3, described through the shared"
                        + " renderer at both nesting levels; document: " + document);
    }

    @Test
    @DisplayName("Sensitivity proof: a non-nested any-setter extras value is unaffected")
    void plainAnySetterExtrasValueUnaffectedByNestedMapFixture() {
        JsonNode document = inputDocument(PlainMapExtrasHolder.class);

        assertEquals(
                "{\"type\":\"string\"}",
                document.path("additionalProperties").toString(),
                "a plain, non-nested Map<String, String> any-setter extras value must render"
                        + " {\"type\":\"string\"}, unaffected by the nested-map fixture; document: " + document);
    }

    // --- Review fix (Critical): a Map subclass that reorders its own type parameters must resolve the
    // value slot through the type-parameter binding Jackson itself resolves, not through the fixed
    // positional index [1] of the declaration site's own type arguments; see
    // ValuePositionRenderer.mapValueSlot(AnnotatedType), formerly a positional lookup ---

    @Test
    @DisplayName("A reordered Map subclass's own type-use constraint on the value slot is rendered")
    void reorderedMapSubclassTypeUseConstraintOnTheValueSlotIsRendered() {
        JsonNode document = inputDocument(ReorderedByNumberHolder.class);
        JsonNode additional =
                additionalProperties(document, property(document, "byNumber"), "ReorderedByNumberHolder.byNumber");

        assertEquals(
                "{\"maxLength\":3,\"type\":\"string\"}",
                additional.toString(),
                "byNumber's value slot (V, written first on Reordered<V, K>) carries @Size(max = 3): the"
                        + " overlay must be read off the type-parameter binding Jackson itself resolves for"
                        + " the value (index 0 of the written arguments), not off the fixed positional index"
                        + " [1], which is the key slot for this reordered subclass; document: " + document);
    }

    @Test
    @DisplayName("A reordered Map subclass's key-slot annotation is never applied to the value")
    void reorderedMapSubclassKeySlotAnnotationIsNeverAppliedToTheValue() {
        JsonNode document = inputDocument(ReorderedByLabelHolder.class);
        JsonNode additional =
                additionalProperties(document, property(document, "byLabel"), "ReorderedByLabelHolder.byLabel");

        assertEquals(
                "{\"type\":\"string\"}",
                additional.toString(),
                "byLabel's key slot (K, written second on Reordered<V, K>) carries @Size(max = 3), not the"
                        + " value slot (String): the overlay must never be misapplied onto the value merely"
                        + " because it sits at the fixed positional index [1]; document: " + document);
    }

    @Test
    @DisplayName("A reordered Map subclass any-setter's own type-use constraint on the value slot is rendered")
    void reorderedMapSubclassAnySetterTypeUseConstraintOnTheValueSlotIsRendered() {
        JsonNode document = inputDocument(ReorderedAnySetterByNumberHolder.class);
        JsonNode additional =
                additionalProperties(document, document, "ReorderedAnySetterByNumberHolder (any-setter extras)");

        assertEquals(
                "{\"maxLength\":3,\"type\":\"string\"}",
                additional.toString(),
                "the any-setter's own value slot (V, written first) carries @Size(max = 3): the overlay"
                        + " must reach the same value-slot binding through the member-based"
                        + " mapValueSlotOfMember(Member), formerly a reuse of the same positional lookup; document: "
                        + document);
    }

    @Test
    @DisplayName("A reordered Map subclass any-setter's own key-slot annotation is never applied to the value")
    void reorderedMapSubclassAnySetterKeySlotAnnotationIsNeverAppliedToTheValue() {
        JsonNode document = inputDocument(ReorderedAnySetterByLabelHolder.class);
        JsonNode additional =
                additionalProperties(document, document, "ReorderedAnySetterByLabelHolder (any-setter extras)");

        assertEquals(
                "{\"type\":\"string\"}",
                additional.toString(),
                "the any-setter's own key slot (K, written second) carries @Size(max = 3), not the value"
                        + " slot (String): it must never be misapplied onto the value; document: " + document);
    }

    @Test
    @DisplayName("A two-level reordered Map subclass chain's own value-slot constraint is rendered")
    void reorderedMapSubclassSupertypeChainTypeUseConstraintOnTheValueSlotIsRendered() {
        JsonNode document = inputDocument(ReorderedTagsHolder.class);
        JsonNode additional =
                additionalProperties(document, property(document, "byNumber"), "ReorderedTagsHolder.byNumber");

        assertEquals(
                "{\"maxLength\":3,\"type\":\"string\"}",
                additional.toString(),
                "ReorderedTags extends Reordered<@Size(max = 3) String, Integer>: the intermediate"
                        + " supertype's own value slot sits at index 0 of its written arguments (V is written"
                        + " first on Reordered<V, K>); the supertype-chain overlay source"
                        + " (mapValueSlotOfClass, formerly a positional supertype-chain lookup) must"
                        + " resolve the same value-slot binding, not the fixed positional index [1]; document: "
                        + document);
    }

    // --- Optional/nullability helpers, mirroring OptionalExtrasAndMapValueNullTest ---

    private static boolean isNullMarker(JsonNode node) {
        return "null".equals(node.path("type").asText(null));
    }

    private static boolean isMarkedNullable(JsonNode schema) {
        JsonNode anyOf = schema.path("anyOf");
        if (anyOf.isArray()) {
            for (JsonNode branch : anyOf) {
                if (isNullMarker(branch)) {
                    return true;
                }
            }
            return false;
        }
        JsonNode type = schema.path("type");
        if (type.isArray()) {
            for (JsonNode entry : type) {
                if ("null".equals(entry.asText(null))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static JsonNode nonNullBranch(JsonNode document, JsonNode schema) {
        JsonNode anyOf = schema.path("anyOf");
        if (anyOf.isArray()) {
            for (JsonNode branch : anyOf) {
                if (!isNullMarker(branch)) {
                    return resolve(document, branch);
                }
            }
        }
        return resolve(document, schema);
    }

    // --- Warning-capture helper (architecture round-3 R3) ---

    /**
     * Runs {@code generation}, capturing every {@code WARNING}-level record published through the
     * emitting class's own {@code System.Logger}-backed JUL logger while it runs.
     *
     * @param warnings   the accumulator, appended in publish order
     * @param generation the generation action, returning the generated document
     * @return the document {@code generation} returned
     */
    private static JsonNode captureWarnings(List<String> warnings, java.util.function.Supplier<JsonNode> generation) {
        Logger logger = Logger.getLogger(InputPropertyDescriber.class.getName());
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    warnings.add(String.valueOf(record.getMessage()));
                }
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        Level previousLevel = logger.getLevel();
        logger.setLevel(Level.ALL);
        logger.addHandler(handler);
        try {
            return generation.get();
        } finally {
            logger.setLevel(previousLevel);
            logger.removeHandler(handler);
        }
    }

    // --- Override-profile helper (TP-005 companion) ---

    private static JsonMapperProfile overrideProfile(Class<?> overriddenClass, JsonSchemaFragment fragment) {
        List<JsonSchemaTypeOverride> overrides = List.of(JsonSchemaTypeOverride.input(overriddenClass, fragment));
        return new JsonMapperProfile() {
            @Override
            public JsonProfileId id() {
                return JsonProfileId.of("t003-tp005-override");
            }

            @Override
            public ObjectMapper mapper() {
                return new ObjectMapper();
            }

            @Override
            public List<JsonSchemaTypeOverride> jsonSchemaTypeOverrides() {
                return overrides;
            }
        };
    }

    // --- Fixtures: TP-001 ---

    /** S2b — the ordinary-map-property type-use fixture. */
    static final class S2bTypeUseHolder {

        /** The type-use-constrained map value under test. */
        public Map<String, @Size(max = 3) String> tags;
    }

    /** N1 — two members of the same underlying Map type, one constrained, one not. */
    static final class TypeUseLeakageHolder {

        /** The type-use-constrained member. */
        public Map<String, @Size(max = 3) String> constrained;

        /** The unconstrained sibling of the same underlying Map<String, String> type. */
        public Map<String, String> plain;
    }

    /** A Map subclass carrying a type-use constraint on its own supertype's type argument. */
    static class Tags extends HashMap<String, @Size(max = 3) String> {}

    /** A body type describing {@link Tags} at a named property position. */
    static final class TagsHolder {

        /** The described property. */
        public Tags tags;
    }

    /** A further subclass with no type argument of its own: the overlay source must follow the chain. */
    static class A extends Tags {}

    // --- Fixtures: TP-002 ---

    /** {@code Plain}'s own declared constraint, translated exactly as for a non-map position. */
    static final class Plain {

        /** The constraint a map's bean value type must still describe. */
        @Size(max = 3)
        public String name;
    }

    /** S2a — a Map<String, Bean> property. */
    static final class S2aBeanHolder {

        /** The bean-valued map under test. */
        public Map<String, Plain> labels;
    }

    /** S2d — a Map<String, Optional<Bean>> property. */
    static final class S2dOptionalBeanHolder {

        /** The Optional-bean-valued map under test. */
        public Map<String, Optional<Plain>> opts;
    }

    /** S2c — a Map<String, JsonNode> property: the unconstrained-value control. */
    static final class S2cJsonNodeHolder {

        /** The opaque-valued map under test. */
        public Map<String, JsonNode> raw;
    }

    // --- Fixtures: TP-005 ---

    /** A value type whose class-level deserializer override already fails router construction (F1). */
    @JsonDeserialize(using = RefusedValue.Deserializer.class)
    static final class RefusedValue {

        /** A named member, so the type looks bean-like beside its own deserializer override. */
        public String name;

        static final class Deserializer extends StdDeserializer<RefusedValue> {
            Deserializer() {
                super(RefusedValue.class);
            }

            @Override
            public RefusedValue deserialize(JsonParser parser, DeserializationContext ctxt) throws IOException {
                throw new UnsupportedOperationException("not exercised");
            }
        }
    }

    /** A map property whose value type is the refused type above. */
    static final class RefusedMapValueHolder {

        /** The map value position under test. */
        public Map<String, RefusedValue> values;
    }

    // --- Fixtures: TP-007 (mirroring the eight hardened-annotation-shapes MCP goldens) ---

    /** Member-level Swagger additionalProperties = TRUE on a map-valued field. */
    static final class MemberLevelTrue {

        /** An ordinary property. */
        public String name;

        /** The annotated member. */
        @Schema(additionalProperties = Schema.AdditionalPropertiesValue.TRUE)
        public Map<String, String> attributes = new LinkedHashMap<>();
    }

    /** Member-level Swagger additionalProperties = FALSE on a map-valued field. */
    static final class MemberLevelFalse {

        /** An ordinary property. */
        public String name;

        /** The annotated member. */
        @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
        public Map<String, String> attributes = new LinkedHashMap<>();
    }

    /** Getter-level Swagger additionalProperties = TRUE. */
    static final class GetterLevelTrue {

        private Map<String, String> attributes = new LinkedHashMap<>();

        /**
         * Returns the annotated map property.
         *
         * @return the attributes
         */
        @Schema(additionalProperties = Schema.AdditionalPropertiesValue.TRUE)
        public Map<String, String> getAttributes() {
            return attributes;
        }

        /**
         * Sets the annotated map property.
         *
         * @param attributes the attributes
         */
        public void setAttributes(Map<String, String> attributes) {
            this.attributes = attributes;
        }
    }

    /** Getter-level Swagger additionalProperties = FALSE. */
    static final class GetterLevelFalse {

        private Map<String, String> attributes = new LinkedHashMap<>();

        /**
         * Returns the annotated map property.
         *
         * @return the attributes
         */
        @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
        public Map<String, String> getAttributes() {
            return attributes;
        }

        /**
         * Sets the annotated map property.
         *
         * @param attributes the attributes
         */
        public void setAttributes(Map<String, String> attributes) {
            this.attributes = attributes;
        }
    }

    /** The no-annotation control: identical shape, no Swagger annotation at all. */
    static final class NoAnnotationControl {

        /** An ordinary property. */
        public String name;

        /** The unannotated member. */
        public Map<String, String> attributes = new LinkedHashMap<>();
    }

    /** Nests {@link MemberLevelTrue} under a holder property, the "-nested" golden's own position. */
    static final class NestedHolderOfMemberLevelTrue {

        /** The nested shape. */
        public MemberLevelTrue nested;

        /** An ordinary property beside it. */
        public String label;
    }

    /** Nests {@link MemberLevelFalse} under a holder property. */
    static final class NestedHolderOfMemberLevelFalse {

        /** The nested shape. */
        public MemberLevelFalse nested;

        /** An ordinary property beside it. */
        public String label;
    }

    /** Nests {@link GetterLevelTrue} under a holder property. */
    static final class NestedHolderOfGetterLevelTrue {

        /** The nested shape. */
        public GetterLevelTrue nested;

        /** An ordinary property beside it. */
        public String label;
    }

    /** Nests {@link GetterLevelFalse} under a holder property. */
    static final class NestedHolderOfGetterLevelFalse {

        /** The nested shape. */
        public GetterLevelFalse nested;

        /** An ordinary property beside it. */
        public String label;
    }

    /** A bean (non-Map) value type carrying a class-level FALSE: TP-007's own sensitivity control. */
    @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    static final class ClosedBean {

        /** An ordinary property. */
        public String name;
    }

    /** Holds the closed bean at a nested position. */
    static final class ClosedBeanHolder {

        /** The nested closed bean. */
        public ClosedBean detail;
    }

    // --- Fixtures: TP-008 (N9) ---

    /** An any-setter whose extras value is itself a nested, type-use-constrained map. */
    static final class NestedMapExtrasHolder {

        /** An ordinary property. */
        public String name;

        /** The any-setter's backing storage: a nested map value (N9). */
        @JsonAnySetter
        public Map<String, Map<String, @Size(max = 3) String>> extras = new LinkedHashMap<>();
    }

    /** A plain, non-nested any-setter extras value: TP-008's own sensitivity control. */
    static final class PlainMapExtrasHolder {

        /** An ordinary property. */
        public String name;

        /** The any-setter's backing storage: a plain, non-nested map value. */
        @JsonAnySetter
        public Map<String, String> extras = new LinkedHashMap<>();
    }

    // --- Fixtures: review fix (Critical) — a reordered Map subclass's own value slot ---

    /**
     * A map subclass whose own type parameters are written value-first ({@code V, K}), while its
     * supertype binds them key-first ({@code LinkedHashMap<K, V>}) — the same shape {@link
     * AnySetterMapSubclassValueTypeTest.Reversed} exercises without annotations. The value slot ({@code
     * V}) sits at index 0 of this class's own written type arguments; index 1 is the key slot.
     *
     * @param <V> the value type, written first
     * @param <K> the key type, written second
     */
    static class Reordered<V, K> extends LinkedHashMap<K, V> {}

    /** A named property whose value-slot (index 0) type argument carries the type-use constraint. */
    static final class ReorderedByNumberHolder {

        /** The value slot (String, index 0) is constrained; the key slot (Integer) is not. */
        public Reordered<@Size(max = 3) String, Integer> byNumber;
    }

    /** A named property whose key-slot (index 1) type argument carries the type-use constraint. */
    static final class ReorderedByLabelHolder {

        /** The key slot (Integer, index 1) is constrained; the value slot (String) is not. */
        public Reordered<String, @Size(max = 3) Integer> byLabel;
    }

    /** An any-setter whose value-slot (index 0) type argument carries the type-use constraint. */
    static final class ReorderedAnySetterByNumberHolder {

        /** An ordinary property. */
        public String name;

        /** The any-setter's backing storage: the value slot (index 0) is constrained. */
        @JsonAnySetter
        public Reordered<@Size(max = 3) String, Integer> extras = new Reordered<>();
    }

    /** An any-setter whose key-slot (index 1) type argument carries the type-use constraint. */
    static final class ReorderedAnySetterByLabelHolder {

        /** An ordinary property. */
        public String name;

        /** The any-setter's backing storage: the key slot (index 1) is constrained. */
        @JsonAnySetter
        public Reordered<String, @Size(max = 3) Integer> extras = new Reordered<>();
    }

    /**
     * A further subclass binding {@link Reordered}'s own value slot at the intermediate supertype
     * level: the overlay source is this class's own {@code getAnnotatedSuperclass()}, {@code
     * Reordered<@Size(max = 3) String, Integer>}, whose value slot (V) sits at index 0.
     */
    static class ReorderedTags extends Reordered<@Size(max = 3) String, Integer> {}

    /** A body type describing {@link ReorderedTags} at a named property position. */
    static final class ReorderedTagsHolder {

        /** The described property. */
        public ReorderedTags byNumber;
    }
}
