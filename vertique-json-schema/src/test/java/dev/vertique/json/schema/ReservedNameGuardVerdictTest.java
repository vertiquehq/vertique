// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.json.JsonMapperProfiles;
import io.swagger.v3.oas.annotations.media.Schema;
import io.vertx.core.json.JsonObject;
import io.vertx.json.schema.Draft;
import io.vertx.json.schema.JsonSchema;
import io.vertx.json.schema.JsonSchemaOptions;
import io.vertx.json.schema.OutputFormat;
import io.vertx.json.schema.OutputUnit;
import io.vertx.json.schema.Validator;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Pins the reserved-name guard's engine-visible behavior: a corpus of one-key request bodies and their
 * vertx-json-schema verdicts, captured while the case-insensitive guard was one combined pattern and
 * unchanged now that the reserved-name refusal is an {@code allOf} entry of its own beside the
 * non-ASCII refusal; and the number of schema positions under a root object's {@code propertyNames}
 * that carry a string {@code pattern} for one key, which that separation raised from two to three for
 * a case-insensitive type with a reserved name.
 */
class ReservedNameGuardVerdictTest {

    // ---------------------------------------------------------------- shared fixtures

    /**
     * Case-insensitively bound, with a size-bounded published member and a hidden member that is
     * therefore reserved, and extras described through a method-level any-setter over an unannotated
     * private map.
     */
    @JsonFormat(with = JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
    static final class CaseInsensitiveHiddenDto {

        @Size(max = 3)
        public String name;

        @Schema(hidden = true)
        public String secret;

        private final Map<String, Object> extras = new LinkedHashMap<>();

        @JsonAnySetter
        private void putExtra(String key, Object value) {
            extras.put(key, value);
        }
    }

    /**
     * Case-sensitively bound, with a published member, a hidden member, and an ignored member — both
     * of the latter therefore reserved — and extras described through a method-level any-setter over an
     * unannotated private map.
     */
    static final class HiddenAndIgnoredDto {

        public String name;

        @Schema(hidden = true)
        public String secretField;

        @JsonIgnore
        public String ignoredField;

        private final Map<String, Object> extras = new LinkedHashMap<>();

        @JsonAnySetter
        private void putExtra(String key, Object value) {
            extras.put(key, value);
        }
    }

    /** Case-insensitively bound, extras described, with nothing hidden or ignored to reserve. */
    @JsonFormat(with = JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
    static final class CaseInsensitiveNothingReservedDto {

        @Size(max = 3)
        public String name;

        private final Map<String, Object> extras = new LinkedHashMap<>();

        @JsonAnySetter
        private void putExtra(String key, Object value) {
            extras.put(key, value);
        }
    }

    /** Case-insensitively bound, closed: no any-setter, so extras are never described. */
    @JsonFormat(with = JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
    static final class ClosedCaseInsensitiveDto {

        @Size(max = 3)
        public String name;
    }

    // ---------------------------------------------------------------- awkward key constants

    /**
     * KELVIN SIGN, U+212A: folds to ASCII {@code 'k'} under Java's locale-independent case mapping, so
     * a key spelled with it is confusable for a {@code 'k'}-led word. Built from its code point, never
     * written as a literal character or a source escape: the project's code formatter rewrites either
     * back into a literal, largely invisible character.
     */
    private static final String KELVIN_SIGN = Character.toString(0x212A);

    /** LATIN SMALL LETTER A WITH MACRON, U+0101: a non-ASCII confusable for a plain {@code 'a'}. */
    private static final String LATIN_SMALL_A_WITH_MACRON = Character.toString(0x0101);

    /** LINE FEED, built from its code point for the same reason as {@link #KELVIN_SIGN}. */
    private static final String LINE_FEED = Character.toString(0x0A);

    // ---------------------------------------------------------------- generation and validation helpers

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonMapperProfile profile() {
        return JsonMapperProfiles.of(JsonProfileId.of("reserved-name-guard-verdict-test"), new ObjectMapper());
    }

    private static String canonical(Class<?> type) {
        return AnnotationJsonSchemaGenerator.forInputProfile(profile()).generateCanonical(type);
    }

    private static JsonObject canonicalSchema(Class<?> type) {
        return new JsonObject(canonical(type));
    }

    private static JsonNode canonicalDocument(Class<?> type) {
        try {
            return MAPPER.readTree(canonical(type));
        } catch (JsonProcessingException malformed) {
            throw new IllegalStateException("the generator must produce valid JSON", malformed);
        }
    }

    /**
     * Compiles the real gate's validator over a fresh copy of {@code schema}, using the gate's own
     * options: Draft 2020-12, base URI {@code https://vertique.local/}, {@link OutputFormat#Basic}.
     */
    private static Validator compileValidator(JsonObject schema) {
        JsonSchemaOptions options = new JsonSchemaOptions()
                .setDraft(Draft.DRAFT202012)
                .setBaseUri("https://vertique.local/")
                .setOutputFormat(OutputFormat.Basic);
        return Validator.create(JsonSchema.of(schema.copy()), options);
    }

    /** One compiled validator per fixture type, built once and reused across the corpus's rows. */
    private static final Map<Class<?>, Validator> VALIDATORS = new ConcurrentHashMap<>();

    private static Validator validatorFor(Class<?> type) {
        return VALIDATORS.computeIfAbsent(type, t -> compileValidator(canonicalSchema(t)));
    }

    // ---------------------------------------------------------------- engine verdicts

    @ParameterizedTest(name = "{0}")
    @MethodSource("engineVerdictCorpus")
    @DisplayName("Engine verdicts over one-key instances of the guarded fixtures equal their pinned values")
    void separatedGuardKeepsTheEnginesVerdicts(
            String label, Class<?> fixture, String key, Object value, boolean expectedValid) {
        Validator validator = validatorFor(fixture);
        OutputUnit result = validator.validate(new JsonObject().put(key, value));
        assertEquals(
                expectedValid,
                Boolean.TRUE.equals(result.getValid()),
                () -> label + ": expected valid=" + expectedValid + " for "
                        + JsonObject.of("key", key).encode());
    }

    private static Stream<Arguments> engineVerdictCorpus() {
        return Stream.of(
                // case-insensitive: the reserved name, exact and case-folded
                Arguments.of(
                        "the reserved name is refused under its exact spelling",
                        CaseInsensitiveHiddenDto.class,
                        "secret",
                        1,
                        false),
                Arguments.of(
                        "the reserved name is refused under its uppercase fold",
                        CaseInsensitiveHiddenDto.class,
                        "SECRET",
                        1,
                        false),
                Arguments.of(
                        "the reserved name is refused under a mixed-case fold",
                        CaseInsensitiveHiddenDto.class,
                        "sEcReT",
                        1,
                        false),
                // case-insensitive: near misses that break the reserved fold, so ordinary extras
                Arguments.of(
                        "a trailing line feed breaks the reserved fold, so an ordinary extra",
                        CaseInsensitiveHiddenDto.class,
                        "secret" + LINE_FEED,
                        1,
                        true),
                Arguments.of(
                        "a trailing extra character breaks the reserved fold, so an ordinary extra",
                        CaseInsensitiveHiddenDto.class,
                        "secrets",
                        1,
                        true),
                Arguments.of(
                        "a leading extra character breaks the reserved fold, so an ordinary extra",
                        CaseInsensitiveHiddenDto.class,
                        "xsecret",
                        1,
                        true),
                // case-insensitive: non-ASCII confusables, refused outright regardless of what they fold to
                Arguments.of(
                        "a Kelvin-sign confusable for 'k' is refused outright as non-ASCII",
                        CaseInsensitiveHiddenDto.class,
                        KELVIN_SIGN + "ey",
                        1,
                        false),
                Arguments.of(
                        "a macron confusable for 'a' is refused outright as non-ASCII",
                        CaseInsensitiveHiddenDto.class,
                        "n" + LATIN_SMALL_A_WITH_MACRON + "me",
                        1,
                        false),
                // case-insensitive: the published name, within and beyond its size bound
                Arguments.of(
                        "the published name within its size bound is valid",
                        CaseInsensitiveHiddenDto.class,
                        "name",
                        "ab",
                        true),
                Arguments.of(
                        "the published name's uppercase fold within its size bound is valid",
                        CaseInsensitiveHiddenDto.class,
                        "NAME",
                        "ab",
                        true),
                Arguments.of(
                        "the published name's uppercase fold beyond its size bound is invalid",
                        CaseInsensitiveHiddenDto.class,
                        "NAME",
                        "abcd",
                        false),
                Arguments.of(
                        "an unreserved key is an ordinary extra", CaseInsensitiveHiddenDto.class, "extra", 1, true),
                // case-sensitive: exact-name reservation only
                Arguments.of(
                        "a reserved hidden field name is refused",
                        HiddenAndIgnoredDto.class,
                        "secretField",
                        "x",
                        false),
                Arguments.of(
                        "a reserved ignored field name is refused",
                        HiddenAndIgnoredDto.class,
                        "ignoredField",
                        "x",
                        false),
                Arguments.of(
                        "a differently-cased spelling is not reserved case-sensitively, so an ordinary extra",
                        HiddenAndIgnoredDto.class,
                        "SECRETFIELD",
                        "x",
                        true),
                Arguments.of("an unreserved key is an ordinary extra", HiddenAndIgnoredDto.class, "other", "x", true));
    }

    // ---------------------------------------------------------------- bounded pattern positions

    @ParameterizedTest(name = "{0}")
    @MethodSource("boundedPositionCorpus")
    @DisplayName("The number of bounded pattern positions one key reaches under propertyNames equals its pinned count")
    void separatedGuardAddsOneBoundedPatternPositionPerKey(String label, Class<?> fixture, int expectedPositions) {
        JsonNode document = canonicalDocument(fixture);
        // Every fixture in this corpus either refuses or reserves at least a non-ASCII key, so every
        // one of them must generate a propertyNames object; without this, a document that generated no
        // propertyNames at all would still count 0 positions and pass the HiddenAndIgnoredDto row for
        // the wrong reason — a missing guard rather than a guard with no pattern under it.
        assertInstanceOf(
                ObjectNode.class,
                document.path("propertyNames"),
                () -> label + " must generate a propertyNames object, or the position count below is vacuous;"
                        + " document: " + document);
        int positions = boundedKeyPositions(document);
        assertEquals(expectedPositions, positions, () -> label + "; document: " + document);
    }

    private static Stream<Arguments> boundedPositionCorpus() {
        // Multiplied out (recorded in evidence alongside these counts): once a case-insensitive type's
        // reserved-name key reaches three bounded positions instead of two, 30 extra keys of 4,000
        // characters count 360,000 characters toward the per-request pattern-input total rather than
        // 240,000 — each bounded position counts the whole 4,000-character key again.
        return Stream.of(
                Arguments.of("a case-insensitive type with a reserved name", CaseInsensitiveHiddenDto.class, 3),
                Arguments.of(
                        "a case-insensitive type with nothing reserved", CaseInsensitiveNothingReservedDto.class, 2),
                Arguments.of("a closed case-insensitive type", ClosedCaseInsensitiveDto.class, 2),
                Arguments.of("a case-sensitive type with reserved names", HiddenAndIgnoredDto.class, 0));
    }

    /**
     * Applies the pattern-input counting rule to one key of {@code document}'s root object: a schema
     * node with a string {@code pattern} bounds and counts the string reaching it, and a node with a
     * non-empty {@code patternProperties} counts each key once more. Only the root's
     * {@code propertyNames} subschema is walked, over schema positions ({@link SchemaPositions}) rather
     * than literal document data, since that is the guard's own contribution to the count; the root's
     * {@code patternProperties} object is checked for emptiness only, never descended.
     *
     * @param document the generated canonical document to inspect
     * @return the number of bounded, counted positions one key of the root object reaches
     */
    private static int boundedKeyPositions(JsonNode document) {
        JsonNode propertyNames = document.path("propertyNames");
        int[] patternNodes = {0};
        if (propertyNames.isObject()) {
            SchemaPositions.visitSchemaHeads(propertyNames, (node, path) -> {
                JsonNode pattern = node.get("pattern");
                if (pattern != null && pattern.isTextual()) {
                    patternNodes[0]++;
                }
            });
        }
        JsonNode patternProperties = document.path("patternProperties");
        boolean hasPatternProperties = patternProperties.isObject() && !patternProperties.isEmpty();
        return patternNodes[0] + (hasPatternProperties ? 1 : 0);
    }
}
