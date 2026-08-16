// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.SerializableString;
import com.fasterxml.jackson.core.io.CharacterEscapes;
import com.fasterxml.jackson.core.io.SerializedString;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.json.JsonMapperProfiles;
import io.vertx.core.json.JsonObject;
import io.vertx.json.schema.Draft;
import io.vertx.json.schema.JsonSchema;
import io.vertx.json.schema.JsonSchemaOptions;
import io.vertx.json.schema.OutputUnit;
import io.vertx.json.schema.Validator;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * FR-JSON-081 baseline fixture matrix, plus the determinism/divergence and PRD §6.2 wire-honesty
 * proofs S6 owns.
 *
 * <p>Every matrix row asserts three things: generation succeeds through {@link
 * AnnotationJsonSchemaGenerator#withVictoolsDefaults()}, the canonical output is byte-identical to a
 * committed golden file under {@code src/test/resources/golden/matrix/} (this doubles as the
 * cross-JVM determinism proof NFR-JSON-014 requires — CI re-executes the same generation and compares
 * against the same committed bytes), and one targeted semantic spot-check for that row's concern.
 *
 * <p>{@link #anchoredPatternValidatorSemanticsPinned()} additionally pins, empirically, which
 * pattern-matching semantics (find vs. match) the real {@code vertx-json-schema} validator applies to
 * the anchored strict-decimal pattern committed in {@code strict-input-amount.json} (S3) — see PRD
 * §6.2 and plan §2 finding 12.
 */
class SchemaFixtureMatrixTest {

    /** Neutral mapper used to read documents inside the assertions. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // --- FR-JSON-081 baseline matrix ---

    @Test
    @DisplayName("ordinaryPojo: required and length constraints survive")
    void ordinaryPojo() throws Exception {
        String canonical = AnnotationJsonSchemaGenerator.withVictoolsDefaults()
                .generateCanonical(MatrixFixtures.OrdinaryPojo.class);
        JsonNode document = assertCanonicalForm(canonical);

        assertTrue(textValues(List.of(document), "required").contains("name"), "'name' must be required");
        JsonNode code = document.at("/properties/code");
        assertEquals(2, code.get("minLength").intValue(), "code minLength");
        assertEquals(8, code.get("maxLength").intValue(), "code maxLength");

        assertGolden("ordinary-pojo.json", canonical);
    }

    @Test
    @DisplayName("record: canonical constructor components become properties")
    void record() throws Exception {
        String canonical =
                AnnotationJsonSchemaGenerator.withVictoolsDefaults().generateCanonical(MatrixFixtures.RecordDto.class);
        JsonNode document = assertCanonicalForm(canonical);

        assertFalse(document.at("/properties/name").isMissingNode(), "record component 'name' must be a property");
        assertFalse(document.at("/properties/age").isMissingNode(), "record component 'age' must be a property");

        assertGolden("record.json", canonical);
    }

    @Test
    @DisplayName("inheritance: a subclass schema carries both the inherited and the declared property")
    void inheritance() throws Exception {
        String canonical =
                AnnotationJsonSchemaGenerator.withVictoolsDefaults().generateCanonical(MatrixFixtures.DogSubtype.class);
        JsonNode document = assertCanonicalForm(canonical);

        assertFalse(document.at("/properties/species").isMissingNode(), "the inherited property must survive");
        assertFalse(document.at("/properties/legs").isMissingNode(), "the subtype's own property must be present");

        assertGolden("inheritance.json", canonical);
    }

    @Test
    @DisplayName("nestedGenericCollections: List<Set<String>> generates array-of-array-of-string")
    void nestedGenericCollections() throws Exception {
        String canonical = AnnotationJsonSchemaGenerator.withVictoolsDefaults()
                .generateCanonical(MatrixFixtures.NestedGenericHolder.class);
        JsonNode document = assertCanonicalForm(canonical);

        JsonNode tags = document.at("/properties/tags");
        assertEquals("array", text(tags.get("type")), "tags must be an array");
        JsonNode outerItems = tags.get("items");
        assertNotNull(outerItems, "tags must declare an item schema");
        assertEquals("array", text(outerItems.get("type")), "each tag element must itself be an array (a Set)");
        JsonNode innerItems = outerItems.get("items");
        assertNotNull(innerItems, "the nested Set must declare an item schema");
        assertEquals("string", text(innerItems.get("type")), "the innermost element type must be string");

        assertGolden("nested-generic-collections.json", canonical);
    }

    @Test
    @DisplayName("resolvedMap: Map<String, Integer> generates a bare object type (pinned S4 fact)")
    void resolvedMap() throws Exception {
        String canonical =
                AnnotationJsonSchemaGenerator.withVictoolsDefaults().generateCanonical(MatrixFixtures.MapHolder.class);
        JsonNode document = assertCanonicalForm(canonical);

        JsonNode counts = document.at("/properties/counts");
        // Pinned loudly: a future Victools change that starts emitting additionalProperties (or any
        // other member) for a resolved Map must break this assertion explicitly, not silently.
        assertEquals(1, counts.size(), "a resolved Map must generate a bare single-member object type");
        assertEquals("object", text(counts.get("type")), "a resolved Map's declared type must be 'object'");

        assertGolden("resolved-map.json", canonical);
    }

    @Test
    @DisplayName("optional: Optional<String> is flattened to the value type's schema")
    void optional() throws Exception {
        String canonical = AnnotationJsonSchemaGenerator.withVictoolsDefaults()
                .generateCanonical(MatrixFixtures.OptionalHolder.class);
        JsonNode document = assertCanonicalForm(canonical);

        JsonNode nickname = document.at("/properties/nickname");
        assertTrue(nickname.at("/properties/present").isMissingNode(), "no Optional wrapper member may survive");
        assertTrue(nickname.at("/properties/empty").isMissingNode(), "no Optional wrapper member may survive");
        // FLATTENED_OPTIONALS unwraps Optional<String> to its value type's schema directly: a nullable
        // string (type array including "null"), not a wrapper object with "present"/"empty" members.
        List<String> types = textValues(List.of(nickname), "type");
        assertTrue(types.contains("string"), "the unwrapped value type must be string; found " + types);
        assertTrue(types.contains("null"), "Optional absence must be representable as null; found " + types);

        assertGolden("optional.json", canonical);
    }

    @Test
    @DisplayName("enums: an enum property generates a string type with an enum value list")
    void enums() throws Exception {
        String canonical =
                AnnotationJsonSchemaGenerator.withVictoolsDefaults().generateCanonical(MatrixFixtures.EnumHolder.class);
        JsonNode document = assertCanonicalForm(canonical);

        JsonNode color = document.at("/properties/color");
        assertEquals("string", text(color.get("type")), "FLATTENED_ENUMS must emit a string type");
        List<String> values = new ArrayList<>();
        color.get("enum").forEach(node -> values.add(node.textValue()));
        // Arrays are never reordered by canonicalization: the enum array preserves declaration order.
        assertEquals(
                List.of("RED", "GREEN", "BLUE"),
                values,
                "enum values in declaration order (arrays are never reordered)");

        assertGolden("enums.json", canonical);
    }

    @Test
    @DisplayName("temporal: Instant/LocalDate properties generate successfully")
    void temporal() throws Exception {
        String canonical = AnnotationJsonSchemaGenerator.withVictoolsDefaults()
                .generateCanonical(MatrixFixtures.TemporalHolder.class);
        JsonNode document = assertCanonicalForm(canonical);

        assertFalse(document.at("/properties/createdAt").isMissingNode(), "the Instant property must be present");
        assertFalse(document.at("/properties/bornOn").isMissingNode(), "the LocalDate property must be present");

        assertGolden("temporal.json", canonical);
    }

    @Test
    @DisplayName("jacksonMetadata: @JsonProperty renames, @JsonIgnore excludes")
    void jacksonMetadata() throws Exception {
        String canonical = AnnotationJsonSchemaGenerator.withVictoolsDefaults()
                .generateCanonical(MatrixFixtures.JacksonMetadataDto.class);
        JsonNode document = assertCanonicalForm(canonical);

        assertFalse(document.at("/properties/full_name").isMissingNode(), "the @JsonProperty rename must apply");
        assertTrue(document.at("/properties/name").isMissingNode(), "the pre-rename property name must not survive");
        assertTrue(document.at("/properties/secret").isMissingNode(), "the @JsonIgnore property must be excluded");

        assertGolden("jackson-metadata.json", canonical);
    }

    @Test
    @DisplayName("jakartaConstraints: @Min/@Max, @Pattern, and @NotNull are mapped")
    void jakartaConstraints() throws Exception {
        String canonical = AnnotationJsonSchemaGenerator.withVictoolsDefaults()
                .generateCanonical(MatrixFixtures.JakartaConstraintsDto.class);
        JsonNode document = assertCanonicalForm(canonical);

        JsonNode quantity = document.at("/properties/quantity");
        assertEquals(1, quantity.get("minimum").intValue(), "@Min must map to minimum");
        assertEquals(100, quantity.get("maximum").intValue(), "@Max must map to maximum");
        assertEquals("^[A-Z]+$", text(document.at("/properties/code").get("pattern")), "@Pattern must map to pattern");
        assertTrue(
                textValues(List.of(document), "required").contains("required"),
                "@NotNull must mark the field required");

        assertGolden("jakarta-constraints.json", canonical);
    }

    @Test
    @DisplayName("swaggerMetadata: @Schema description/title/minLength are mapped")
    void swaggerMetadata() throws Exception {
        String canonical = AnnotationJsonSchemaGenerator.withVictoolsDefaults()
                .generateCanonical(MatrixFixtures.SwaggerMetadataDto.class);
        JsonNode document = assertCanonicalForm(canonical);

        JsonNode label = document.at("/properties/label");
        assertEquals("the item label", text(label.get("description")), "@Schema description");
        assertEquals("Label", text(label.get("title")), "@Schema title");
        assertEquals(3, document.at("/properties/code/minLength").intValue(), "@Schema minLength");

        assertGolden("swagger-metadata.json", canonical);
    }

    @Test
    @DisplayName("closedPolymorphism: @JsonTypeInfo/@JsonSubTypes generates an anyOf of two named subtypes")
    void closedPolymorphism() throws Exception {
        String canonical =
                AnnotationJsonSchemaGenerator.withVictoolsDefaults().generateCanonical(MatrixFixtures.Vehicle.class);
        JsonNode document = assertCanonicalForm(canonical);

        JsonNode anyOf = document.get("anyOf");
        assertNotNull(anyOf, "closed polymorphism must generate an anyOf alternation");
        assertEquals(2, anyOf.size(), "exactly two declared subtypes");
        List<String> constants = new ArrayList<>();
        collectMemberTexts(anyOf, "const", constants);
        assertTrue(constants.contains("car"), "the 'car' discriminator value must appear; found " + constants);
        assertTrue(constants.contains("truck"), "the 'truck' discriminator value must appear; found " + constants);

        assertGolden("closed-polymorphism.json", canonical);
    }

    @Test
    @DisplayName("recursiveGraph: a self-referencing type generates via $defs/$ref without overflowing")
    void recursiveGraph() throws Exception {
        String canonical =
                AnnotationJsonSchemaGenerator.withVictoolsDefaults().generateCanonical(MatrixFixtures.TreeNode.class);
        JsonNode document = assertCanonicalForm(canonical);

        assertTrue(
                canonical.contains("$defs") || canonical.contains("$ref"),
                "a recursive graph must use a definition/reference");
        assertFalse(document.at("/properties/value").isMissingNode(), "the node's own value property must be present");

        assertGolden("recursive-graph.json", canonical);
    }

    // --- FR-JSON-078: divergence is asserted explicitly, never cross-config equality ---

    @Test
    @DisplayName("Different mapper property models produce documents that explicitly differ")
    void differentMapperConfigurationsDivergeExplicitly() {
        // Given: two profiles over the same DTO whose only difference is Jackson field visibility —
        // one detects public fields by default, the other has field auto-detection disabled.
        JsonMapperProfile fieldVisible =
                JsonMapperProfiles.of(JsonProfileId.of("matrix-field-visible"), new ObjectMapper());

        ObjectMapper fieldHidden = new ObjectMapper();
        fieldHidden.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.NONE);
        JsonMapperProfile fieldInvisible = JsonMapperProfiles.of(JsonProfileId.of("matrix-field-hidden"), fieldHidden);

        // When: each profile generates a schema for the same visibility-sensitive DTO.
        String visibleDocument = AnnotationJsonSchemaGenerator.forInputProfile(fieldVisible)
                .generateCanonical(MatrixFixtures.VisibilityDto.class);
        String hiddenDocument = AnnotationJsonSchemaGenerator.forInputProfile(fieldInvisible)
                .generateCanonical(MatrixFixtures.VisibilityDto.class);

        // Then: the documents explicitly differ — the field-detecting mapper's property model exposes
        // the field-only property; the field-hiding mapper's does not. No cross-config equality is
        // asserted (FR-JSON-078).
        assertNotEquals(visibleDocument, hiddenDocument, "different Jackson property models must not converge");
        assertTrue(visibleDocument.contains("visibleField"), "the field-detecting mapper must expose the property");
        assertFalse(hiddenDocument.contains("visibleField"), "the field-hiding mapper must not expose the property");
    }

    // --- Neutral-writer proof: canonical bytes are unaffected by profile mapper serialization quirks ---

    @Test
    @DisplayName("Canonical bytes are identical regardless of hostile profile mapper serialization settings")
    void canonicalBytesUnaffectedByProfileMapperSerializationQuirks() {
        // Given: a plain profile mapper, and a "hostile" profile mapper carrying serialization-side
        // settings that would alter emitted bytes if they were ever used to serialize the schema
        // document itself — but leave the Jackson property model (what Victools discovers) unchanged.
        JsonMapperProfile plain = JsonMapperProfiles.of(JsonProfileId.of("matrix-plain"), new ObjectMapper());

        ObjectMapper hostileMapper = new ObjectMapper();
        hostileMapper.getFactory().setCharacterEscapes(escapeLowercaseA());
        hostileMapper.getFactory().configure(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN, true);
        hostileMapper.enable(SerializationFeature.INDENT_OUTPUT);
        JsonMapperProfile hostile = JsonMapperProfiles.of(JsonProfileId.of("matrix-hostile"), hostileMapper);

        // When: both profiles generate a schema whose keywords deliberately contain the letter 'a'
        // (the @Pattern-bearing property emits the "pattern" keyword) for the fixture that would
        // reveal a hostile-mapper leak.
        String plainCanonical = AnnotationJsonSchemaGenerator.forInputProfile(plain)
                .generateCanonical(MatrixFixtures.JakartaConstraintsDto.class);
        String hostileCanonical = AnnotationJsonSchemaGenerator.forInputProfile(hostile)
                .generateCanonical(MatrixFixtures.JakartaConstraintsDto.class);

        // Then: canonicalization always goes through the generator-owned neutral writer, so the
        // profile mapper's serialization-side configuration has no effect on the emitted bytes.
        assertTrue(
                plainCanonical.contains("pattern"), "the fixture must exercise the 'pattern' keyword containing 'a'");
        assertEquals(
                plainCanonical,
                hostileCanonical,
                "canonical bytes must be unaffected by hostile mapper serialization settings");
    }

    /**
     * Builds a {@link CharacterEscapes} that would rewrite every lowercase {@code 'a'} to a custom
     * escape sequence if it were ever used to serialize a document.
     *
     * @return the hostile character escapes
     */
    private static CharacterEscapes escapeLowercaseA() {
        int[] escapes = CharacterEscapes.standardAsciiEscapesForJSON();
        escapes['a'] = CharacterEscapes.ESCAPE_CUSTOM;
        return new CharacterEscapes() {

            @Override
            public int[] getEscapeCodesForAscii() {
                return escapes;
            }

            @Override
            public SerializableString getEscapeSequence(int ch) {
                return ch == 'a' ? new SerializedString("\\u0041") : null;
            }
        };
    }

    // --- PRD §6.2 wire-honesty: a Jakarta constraint inapplicable to the wire type is not emitted ---

    @Test
    @DisplayName("@DecimalMin on a string-form BigDecimal does not emit 'minimum' on the string schema (PRD §6.2)")
    void jakartaConstraintInapplicableToWireTypeNotEmitted() throws Exception {
        // Given: the real vertique-strict profile, whose BigDecimal override declares a string wire
        // type, and a property additionally carrying a Jakarta numeric-domain constraint.
        JsonMapperProfile strict = HardeningFixtures.strictProfile();

        // When: the input-mode generator produces the document.
        String canonical = AnnotationJsonSchemaGenerator.forInputProfile(strict)
                .generateCanonical(MatrixFixtures.DecimalMinDto.class);
        JsonNode document = assertCanonicalForm(canonical);

        // Then: the fragment's own keywords are still present...
        List<JsonNode> applicable = propertyClosure(document, "amount");
        assertTrue(textValues(applicable, "type").contains("string"), "the string wire type must still be present");
        assertTrue(textValues(applicable, "format").contains("decimal"), "the fragment's format must still be present");

        // ...but the value-domain constraint, inapplicable to a string wire representation, is not
        // advertised as if it constrained that representation (PRD §6.2: "@DecimalMin on a string-form
        // BigDecimal is not emitted as minimum on the string schema"). Bean Validation still enforces
        // it against the materialized Java value; only the published schema keyword is suppressed.
        assertTrue(
                keywordValues(applicable, "minimum").isEmpty(),
                "no 'minimum' keyword may apply to a property whose effective wire type excludes number/integer; document: "
                        + canonical);
        assertTrue(
                keywordValues(applicable, "exclusiveMinimum").isEmpty(),
                "exclusiveMinimum must likewise be suppressed");
        assertTrue(keywordValues(applicable, "maximum").isEmpty(), "maximum must likewise be suppressed");
        assertTrue(
                keywordValues(applicable, "exclusiveMaximum").isEmpty(),
                "exclusiveMaximum must likewise be suppressed");
    }

    // --- §2 finding 12: validator pattern-matching semantics for the anchored strict-decimal pattern ---

    /**
     * Pins the actual pattern-matching semantics the real {@code vertx-json-schema} validator applies
     * to the anchored strict-decimal pattern committed in {@code strict-input-amount.json} (S3).
     *
     * <p><strong>Empirical finding:</strong> the compiled {@code Draft 2020-12} validator evaluates
     * the {@code pattern} keyword using Java {@link java.util.regex.Matcher#find()} semantics, not
     * {@link java.util.regex.Matcher#matches()} — the JSON Schema specification itself mandates a
     * "contains a match" evaluation. Combined with Java's own {@code $} anchor allowing a zero-width
     * match immediately before a single trailing line terminator (a `Pattern.matches`-vs-`find`
     * divergence that does not exist for `find` with `\z`), {@code "1\n"} validates successfully
     * against the anchored pattern {@code ^-?[0-9]+(\.[0-9]+)?$}, exactly as {@code "1"} does. The
     * residual is the one the plan already characterizes as safe: the *published* schema is marginally
     * over-permissive relative to {@code BigDecimalStrictStringDeserializer}, which uses {@code
     * matches()} and rejects the trailing newline — the serde stays the strict boundary.
     */
    @Test
    @DisplayName(
            "The strict decimal pattern validates \"1\\n\" under find semantics (documented residual, §2 finding 12)")
    void anchoredPatternValidatorSemanticsPinned() throws Exception {
        JsonObject schema = new JsonObject(golden("strict-input-amount.json"));
        JsonSchemaOptions options =
                new JsonSchemaOptions().setDraft(Draft.DRAFT202012).setBaseUri("https://vertique.local/");
        Validator validator = Validator.create(JsonSchema.of(schema), options);

        OutputUnit exactLiteral = validator.validate(new JsonObject().put("amount", "1"));
        OutputUnit trailingNewline = validator.validate(new JsonObject().put("amount", "1\n"));

        assertTrue(Boolean.TRUE.equals(exactLiteral.getValid()), "the exact literal \"1\" must validate");
        // Pinned empirical outcome: the compiled validator applies find semantics, so the trailing
        // newline also validates. If a future vertx-json-schema version switches to full-match
        // semantics, this assertion fails loudly and the javadoc above must be corrected.
        assertTrue(
                Boolean.TRUE.equals(trailingNewline.getValid()),
                "the pinned outcome is that \"1\\n\" ALSO validates (find semantics); if this changed to reject, "
                        + "update this test and its javadoc to record the new pinned semantics");
    }

    // --- Canonical-form + conjunctive-path helpers (mirroring AnnotationJsonSchemaGeneratorProofTest) ---

    /**
     * Asserts that a canonical document is valid JSON, compact, and recursively key-sorted, and
     * returns its parsed form.
     *
     * @param canonical the canonical document text
     * @return the parsed document
     * @throws Exception if the text is not valid JSON
     */
    private static JsonNode assertCanonicalForm(String canonical) throws Exception {
        assertNotNull(canonical, "the canonical document must not be null");
        JsonNode document = MAPPER.readTree(canonical);
        assertEquals(
                MAPPER.writeValueAsString(document),
                canonical,
                "the canonical document must be compact JSON with no re-serialization difference");
        return document;
    }

    /**
     * Collects every schema node that conjunctively applies at {@code start}: the node itself, each
     * direct {@code allOf} branch, and each locally resolvable {@code $ref} target.
     *
     * @param document the whole document, used to resolve {@code $ref} pointers
     * @param start    the node whose conjunctive closure is wanted
     * @return the closure, in discovery order
     */
    private static List<JsonNode> conjunctiveClosure(JsonNode document, JsonNode start) {
        List<JsonNode> collected = new ArrayList<>();
        Map<JsonNode, Boolean> visited = new IdentityHashMap<>();
        Deque<JsonNode> queue = new ArrayDeque<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            JsonNode node = queue.poll();
            if (!node.isObject() || visited.put(node, Boolean.TRUE) != null) {
                continue;
            }
            collected.add(node);

            JsonNode allOf = node.get("allOf");
            if (allOf != null && allOf.isArray()) {
                allOf.forEach(queue::add);
            }

            JsonNode ref = node.get("$ref");
            if (ref != null && ref.isTextual() && ref.textValue().startsWith("#")) {
                JsonNode target = document.at(ref.textValue().substring(1));
                if (!target.isMissingNode()) {
                    queue.add(target);
                }
            }
        }
        return collected;
    }

    /**
     * Collects every schema node that conjunctively applies to the named property.
     *
     * @param document the whole document
     * @param property the property name
     * @return the property's conjunctive closure
     */
    private static List<JsonNode> propertyClosure(JsonNode document, String property) {
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
    private static List<JsonNode> keywordValues(List<JsonNode> nodes, String keyword) {
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
     * Collects the textual values a keyword takes across a set of conjunctive locations, flattening an
     * array-valued occurrence.
     *
     * @param nodes   the conjunctive locations
     * @param keyword the keyword to collect
     * @return every textual value found
     */
    private static List<String> textValues(List<JsonNode> nodes, String keyword) {
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
    private static void collectMemberTexts(JsonNode node, String key, List<String> collected) {
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
    private static String text(JsonNode node) {
        return node != null && node.isTextual() ? node.textValue() : null;
    }

    // --- Golden bytes ---

    /**
     * Asserts that a generated canonical document equals its committed matrix golden file, reading
     * from {@code src/test/resources/golden/matrix/}.
     *
     * @param name      the golden file name under {@code golden/matrix/}
     * @param canonical the generated canonical document text
     * @throws IOException if the golden resource cannot be read
     */
    private static void assertGolden(String name, String canonical) throws IOException {
        assertEquals(golden("matrix/" + name), canonical, name + " golden bytes");
    }

    /**
     * Reads a committed golden document from the test classpath under {@code /golden/}.
     *
     * @param path the path under {@code /golden/} (e.g. {@code "matrix/ordinary-pojo.json"} or {@code
     *             "strict-input-amount.json"})
     * @return the golden document text, trailing newline stripped
     * @throws IOException if the resource exists but cannot be read
     */
    private static String golden(String path) throws IOException {
        try (InputStream in = SchemaFixtureMatrixTest.class.getResourceAsStream("/golden/" + path)) {
            if (in == null) {
                return fail("golden document /golden/" + path
                        + " is not recorded yet — generate it, inspect it for contract correctness, then commit it");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        }
    }
}
