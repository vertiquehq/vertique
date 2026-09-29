// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.core.json.JsonSchemaFragment;
import dev.vertique.core.json.JsonSchemaTypeOverride;
import dev.vertique.json.JsonMapperProfiles;
import io.swagger.v3.oas.annotations.media.Schema;
import io.vertx.core.json.JsonObject;
import io.vertx.json.schema.Draft;
import io.vertx.json.schema.JsonSchema;
import io.vertx.json.schema.JsonSchemaOptions;
import io.vertx.json.schema.OutputFormat;
import io.vertx.json.schema.Validator;
import jakarta.validation.constraints.Size;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Verifies the folded {@code patternProperties} copy the input generator publishes for a
 * case-insensitively bound member.
 *
 * <p>The copy's key matches every ASCII casing of the member's wire name except the exact spelling,
 * which {@code properties} already validates, so each casing is checked once. The copy's value is the
 * member's finished {@code properties} value, taken after every definition reference is resolved,
 * whatever the member's type. This class proves the key text and its matching, that a name with no
 * ASCII letter publishes no copy while a name the fold cannot cover still fails generation, that the
 * reserved-name guard still refuses the exact spelling and its manifest follows the key, and that
 * the copy is the resolved member schema for every member shape.
 */
class CaseFoldKeyTest {

    // ---------------------------------------------------------------- fixtures: key text

    /**
     * Case-insensitively bound, with size-bounded members whose wire names are a plain word, a
     * dotted name, a name containing a slash, and a single letter; a member whose name has no letter;
     * a hidden member, which is reserved; and extras described through a method-level any-setter.
     */
    @JsonFormat(with = JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
    static final class FoldKeyDto {

        @Size(max = 3)
        public String name;

        @JsonProperty("a.b")
        @Size(max = 3)
        public String dotted;

        @JsonProperty("in/out")
        @Size(max = 3)
        public String io;

        @JsonProperty("x")
        @Size(max = 3)
        public String single;

        @JsonProperty("123")
        @Size(max = 3)
        public String digits;

        @Schema(hidden = true)
        public String secret;

        private final Map<String, Object> extras = new LinkedHashMap<>();

        @JsonAnySetter
        private void putExtra(String key, Object value) {
            extras.put(key, value);
        }
    }

    /** Case-insensitively bound; every published name lacks an ASCII letter. */
    @JsonFormat(with = JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
    static final class LetterlessDto {

        @JsonProperty("123")
        public String digits;

        @JsonProperty("4-5")
        public String range;
    }

    /** Case-insensitively bound; its one name carries a non-ASCII symbol the fold does not cover. */
    @JsonFormat(with = JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
    static final class EuroDto {

        @JsonProperty("€")
        public String amount;
    }

    /** Case-insensitively bound; its one name carries a non-ASCII letter the fold does not cover. */
    @JsonFormat(with = JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
    static final class AccentDto {

        @JsonProperty("é")
        public String letter;
    }

    // ---------------------------------------------------------------- fixtures: reference members

    /** A member type whose schema is written through a definition reference. */
    static final class Leaf {

        @Size(max = 3)
        public String name;
    }

    static final class ListHolder {

        public List<Leaf> items;
    }

    static final class ArrHolder {

        public Leaf[] arr;
    }

    static final class MapHolder {

        public Map<String, Leaf> byKey;
    }

    static final class OptHolder {

        public Optional<Leaf> opt;
    }

    static final class EnumHolder {

        public DayOfWeek day;
    }

    /** A polymorphic base, described as an {@code anyOf} of its subtypes. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
    @JsonSubTypes({@JsonSubTypes.Type(value = PA.class, name = "a"), @JsonSubTypes.Type(value = PB.class, name = "b")})
    abstract static class PBase {

        @Size(max = 3)
        public String name;
    }

    static final class PA extends PBase {}

    static final class PB extends PBase {}

    static final class PolyHolder {

        public PBase item;
    }

    static final class PolyListHolder {

        public List<PBase> items;
    }

    static final class SelfRef {

        @Size(max = 3)
        public String name;

        public List<SelfRef> kids;
    }

    static final class SharedHolder {

        public Leaf one;

        public Leaf two;
    }

    // ---------------------------------------------------------------- fixtures: nested DTOs

    /** A plain, case-sensitive nested type, used once. */
    static final class Inner {

        @Size(max = 3)
        public String name;
    }

    /** A plain, case-sensitive nested type, used by two members of one holder. */
    static final class Shared {

        @Size(max = 3)
        public String name;
    }

    /** A plain, case-sensitive nested type with a hidden member, so it is reserved, and extras. */
    static final class ReservedInner {

        public String label;

        @Schema(hidden = true)
        public String secret;

        private final Map<String, Object> extras = new LinkedHashMap<>();

        @JsonAnySetter
        private void putExtra(String key, Object value) {
            extras.put(key, value);
        }
    }

    @JsonFormat(with = JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
    static final class NestedHolder {

        public Inner inner;
    }

    @JsonFormat(with = JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
    static final class SharedNestedHolder {

        public Shared first;

        public Shared second;
    }

    @JsonFormat(with = JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
    static final class ReservedNestedHolder {

        public ReservedInner nested;
    }

    // ---------------------------------------------------------------- fixtures: guard copies

    /** A member type with extras described and a hidden member, so it carries a reserved-name guard. */
    static final class GuardedChild {

        public String label;

        @Schema(hidden = true)
        public String secret;

        private final Map<String, Object> extras = new LinkedHashMap<>();

        @JsonAnySetter
        private void putExtra(String key, Object value) {
            extras.put(key, value);
        }
    }

    /**
     * Case-insensitively bound; its one member's wire name carries a slash, and its inline
     * case-insensitive description carries a guard the folded copy therefore repeats.
     */
    @JsonFormat(with = JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
    static final class FoldCopiedGuardHolder {

        @JsonProperty("in/out")
        @JsonFormat(with = JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
        public GuardedChild child;
    }

    /** The type an override fragment carrying the folded-copy keyword is declared for. */
    static final class FragmentTarget {

        public String name;
    }

    // ---------------------------------------------------------------- expected shapes

    /** The end-of-input anchor every generator-built pattern ends with, as the pattern's own text. */
    private static final String END = "(?![\\s\\S])";

    private static final String NAME_KEY = "^(?!name(?![\\s\\S]))[nN][aA][mM][eE](?![\\s\\S])";

    private static final String DOTTED_KEY = "^(?!a\\.b(?![\\s\\S]))[aA]\\.[bB](?![\\s\\S])";

    private static final String SLASHED_KEY = "^(?!in/out(?![\\s\\S]))[iI][nN]/[oO][uU][tT](?![\\s\\S])";

    private static final String SINGLE_KEY = "^(?!x(?![\\s\\S]))[xX](?![\\s\\S])";

    /** The reserved-name refusal for {@code secret}, as the pattern's own text. */
    private static final String SECRET_REFUSAL = "^(?:[sS][eE][cC][rR][eE][tT])" + END;

    // ---------------------------------------------------------------- generation helpers

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonMapperProfile plainProfile() {
        return JsonMapperProfiles.of(JsonProfileId.of("case-fold-key-test"), new ObjectMapper());
    }

    private static JsonMapperProfile mapperWideProfile() {
        return JsonMapperProfiles.of(
                JsonProfileId.of("case-fold-key-test-mapper-wide"),
                new ObjectMapper().configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES, true));
    }

    private static AnnotationJsonSchemaGenerator generatorFor(JsonMapperProfile profile) {
        return AnnotationJsonSchemaGenerator.forInputProfile(profile);
    }

    private static JsonNode read(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException malformed) {
            throw new AssertionError("expected well-formed JSON", malformed);
        }
    }

    private static JsonNode document(AnnotationJsonSchemaGenerator generator, Class<?> type) {
        return read(generator.generateCanonical(type));
    }

    /**
     * Whether vertx-json-schema accepts {@code instance} against {@code schema}, compiled with the
     * REST gate's own options, on a fresh copy of the schema.
     */
    private static boolean engineAccepts(String schema, JsonObject instance) {
        JsonSchemaOptions options = new JsonSchemaOptions()
                .setDraft(Draft.DRAFT202012)
                .setBaseUri("https://vertique.local/")
                .setOutputFormat(OutputFormat.Basic);
        Validator validator = Validator.create(JsonSchema.of(new JsonObject(schema)), options);
        return Boolean.TRUE.equals(validator.validate(instance).getValid());
    }

    /** The published key set of {@code definition}'s {@code patternProperties}, sorted. */
    private static List<String> patternKeys(JsonNode definition) {
        return sortedNames(definition.path("patternProperties"));
    }

    /** The member names of {@code object}, sorted. */
    private static List<String> sortedNames(JsonNode object) {
        TreeSet<String> names = new TreeSet<>();
        object.fieldNames().forEachRemaining(names::add);
        return new ArrayList<>(names);
    }

    /**
     * The value of the one {@code patternProperties} entry of {@code definition} whose key finds the
     * upper-case spelling of {@code member}, or a missing node when there is none. Fails when several
     * entries find it, since the copies of two members must not overlap.
     */
    private static JsonNode foldedCopyOf(JsonNode definition, String member) {
        String otherCasing = member.toUpperCase(Locale.ROOT);
        List<JsonNode> found = new ArrayList<>();
        definition.path("patternProperties").fields().forEachRemaining(entry -> {
            if (Pattern.compile(entry.getKey()).matcher(otherCasing).find()) {
                found.add(entry.getValue());
            }
        });
        assertTrue(found.size() <= 1, () -> "one folded copy per member; found " + found.size() + " for " + member);
        return found.isEmpty() ? MissingNode.getInstance() : found.get(0);
    }

    /** Collects every object key at any depth of {@code node}. */
    private static void collectKeys(JsonNode node, List<String> keys) {
        if (node.isObject()) {
            node.properties().forEach(member -> {
                keys.add(member.getKey());
                collectKeys(member.getValue(), keys);
            });
        } else if (node.isArray()) {
            node.forEach(element -> collectKeys(element, keys));
        }
    }

    // ---------------------------------------------------------------- key text and matching

    @Test
    @DisplayName("A folded copy matches every other casing of its member and never the exact spelling; a name"
            + " with no ASCII letter has no copy; a name the fold cannot cover still fails generation")
    void foldedCopiesExcludeTheExactSpelling() {
        AnnotationJsonSchemaGenerator generator = generatorFor(plainProfile());
        JsonNode dto = document(generator, FoldKeyDto.class);
        JsonNode letterless = document(generator, LetterlessDto.class);

        // (key, input, expected find) rows: the engine applies a pattern with find().
        List<Object[]> table = List.of(
                new Object[] {NAME_KEY, "NAME", true},
                new Object[] {NAME_KEY, "Name", true},
                new Object[] {NAME_KEY, "name", false},
                new Object[] {NAME_KEY, "name\n", false},
                new Object[] {NAME_KEY, "xname", false},
                new Object[] {NAME_KEY, "namex", false},
                new Object[] {DOTTED_KEY, "A.B", true},
                new Object[] {DOTTED_KEY, "a.b", false},
                new Object[] {DOTTED_KEY, "AxB", false},
                new Object[] {SLASHED_KEY, "IN/OUT", true},
                new Object[] {SLASHED_KEY, "in/out", false},
                new Object[] {SINGLE_KEY, "X", true},
                new Object[] {SINGLE_KEY, "x", false});

        JsonSchemaGenerationException euro =
                assertThrows(JsonSchemaGenerationException.class, () -> generator.generateCanonical(EuroDto.class));
        JsonSchemaGenerationException accent =
                assertThrows(JsonSchemaGenerationException.class, () -> generator.generateCanonical(AccentDto.class));

        assertAll(
                () -> assertEquals(
                        List.of(SLASHED_KEY, DOTTED_KEY, NAME_KEY, SINGLE_KEY).stream()
                                .sorted()
                                .toList(),
                        patternKeys(dto),
                        () -> "the folded keys of FoldKeyDto; document: " + dto),
                () -> assertFalse(
                        dto.path("properties").path("123").isMissingNode(), "digits is still a published property"),
                () -> assertEquals(dto.path("properties").path("name"), dto.at(pointerFor(NAME_KEY))),
                () -> assertEquals(dto.path("properties").path("a.b"), dto.at(pointerFor(DOTTED_KEY))),
                () -> assertEquals(dto.path("properties").path("in/out"), dto.at(pointerFor(SLASHED_KEY))),
                () -> assertEquals(dto.path("properties").path("x"), dto.at(pointerFor(SINGLE_KEY))),
                () -> assertNotEquals(
                        0,
                        dto.path("properties").path("name").size(),
                        "the copied schema must be a real schema, or equality proves nothing"),
                () -> assertAll(table.stream().map(row -> (Executable) () -> {
                    String key = (String) row[0];
                    String input = (String) row[1];
                    boolean expected = (boolean) row[2];
                    assertEquals(
                            expected,
                            Pattern.compile(key).matcher(input).find(),
                            () -> "key " + key + " applied to " + input.replace("\n", "\\n"));
                })),
                () -> assertEquals(
                        List.of("123", "4-5"),
                        sortedNames(letterless.path("properties")),
                        () -> "LetterlessDto still publishes both names; document: " + letterless),
                () -> assertFalse(
                        letterless.has("patternProperties"),
                        () -> "a name with no ASCII letter has no folded copy; document: " + letterless),
                () -> assertAll(
                        () -> assertTrue(euro.getMessage().contains(EuroDto.class.getSimpleName()), euro.getMessage()),
                        () -> assertTrue(euro.getMessage().contains("\"€\""), euro.getMessage()),
                        () -> assertTrue(euro.getMessage().contains("non-ASCII"), euro.getMessage())),
                () -> assertAll(
                        () -> assertTrue(
                                accent.getMessage().contains(AccentDto.class.getSimpleName()), accent.getMessage()),
                        () -> assertTrue(accent.getMessage().contains("\"é\""), accent.getMessage()),
                        () -> assertTrue(accent.getMessage().contains("non-ASCII"), accent.getMessage())));
    }

    /** The RFC 6901 pointer to the {@code patternProperties} entry keyed {@code key}. */
    private static String pointerFor(String key) {
        return "/patternProperties/" + key.replace("~", "~0").replace("/", "~1");
    }

    // ---------------------------------------------------------------- the reserved-name guard

    @ParameterizedTest(name = "{0}")
    @MethodSource("guardRows")
    @DisplayName("The reserved-name guard still refuses the exact spelling, and its manifest follows the key")
    void reservedNameGuardKeepsTheExactSpelling(String label, Executable row) throws Throwable {
        row.execute();
    }

    private static Stream<Arguments> guardRows() {
        List<Arguments> rows = new ArrayList<>();
        for (String spelling : List.of("secret", "SECRET", "sEcReT")) {
            rows.add(Arguments.of("the guard refuses the key " + spelling, (Executable) () -> {
                String schema = generatorFor(plainProfile()).generateCanonical(FoldKeyDto.class);
                assertFalse(
                        engineAccepts(schema, new JsonObject().put(spelling, 1)),
                        () -> "the key " + spelling + " must be refused; schema: " + schema);
            }));
        }
        rows.add(Arguments.of(
                "the guard pattern folds the reserved name and does not exclude its exact spelling",
                (Executable) () -> {
                    JsonNode dto = document(generatorFor(plainProfile()), FoldKeyDto.class);
                    assertEquals(
                            SECRET_REFUSAL,
                            dto.at("/propertyNames/allOf/1/not/pattern").asText(null),
                            () -> "document: " + dto);
                }));
        rows.add(Arguments.of("a copied guard is listed at the new key's pointer", (Executable) () -> {
            CanonicalSchema described = generatorFor(plainProfile()).describe(FoldCopiedGuardHolder.class);
            assertEquals(
                    List.of(
                            "/patternProperties/^(?!in~1out(?![\\s\\S]))[iI][nN]~1[oO][uU][tT](?![\\s\\S])"
                                    + "/propertyNames/allOf/1",
                            "/properties/in~1out/propertyNames/allOf/1"),
                    described.redactionManifest().pointers(),
                    () -> "document: " + described.json());
            assertTrue(described.redactionManifest().matches(described.json()));
        }));
        rows.add(Arguments.of("a guard copied from a nested type is listed under the folded key", (Executable) () -> {
            CanonicalSchema described = generatorFor(plainProfile()).describe(ReservedNestedHolder.class);
            List<String> expected = new ArrayList<>();
            collectSecretGuardPointers(read(described.json()), "", expected);
            expected.sort(String::compareTo);
            assertAll(
                    () -> assertEquals(expected, described.redactionManifest().pointers(), described.json()),
                    () -> assertTrue(
                            expected.stream().anyMatch(pointer -> pointer.startsWith("/patternProperties/")),
                            () -> "at least one guard sits under the folded copy; found " + expected + " in "
                                    + described.json()),
                    () -> assertTrue(described.redactionManifest().matches(described.json())));
        }));
        return rows.stream();
    }

    /**
     * Collects, by an independent walk, the RFC 6901 pointer of every node whose {@code propertyNames}
     * carries the guard for {@code secret}: the folded refusal at {@code allOf[1]} of a
     * case-insensitive type, or the exact {@code not.enum} form of a case-sensitive one.
     */
    private static void collectSecretGuardPointers(JsonNode node, String pointer, List<String> found) {
        if (node.isObject()) {
            JsonNode names = node.path("propertyNames");
            if (SECRET_REFUSAL.equals(names.at("/allOf/1/not/pattern").asText(null))) {
                found.add(pointer + "/propertyNames/allOf/1");
            } else if (names.at("/not/enum").isArray()) {
                for (JsonNode reserved : names.at("/not/enum")) {
                    if ("secret".equals(reserved.asText())) {
                        found.add(pointer + "/propertyNames");
                    }
                }
            }
            node.properties()
                    .forEach(member -> collectSecretGuardPointers(
                            member.getValue(),
                            pointer + "/" + member.getKey().replace("~", "~0").replace("/", "~1"),
                            found));
        } else if (node.isArray()) {
            for (int index = 0; index < node.size(); index++) {
                collectSecretGuardPointers(node.get(index), pointer + "/" + index, found);
            }
        }
    }

    // ---------------------------------------------------------------- resolved copies

    @ParameterizedTest(name = "{0}")
    @MethodSource("resolvedCopyRows")
    @DisplayName("A folded copy is the member's resolved schema, whatever the member's type")
    void foldedCopiesCarryTheResolvedMemberSchema(String label, Executable row) throws Throwable {
        row.execute();
    }

    private static Stream<Arguments> resolvedCopyRows() {
        List<Arguments> rows = new ArrayList<>();
        // (shape, member) rows over the mapper-wide profile.
        rows.add(shapeRow(ListHolder.class, mapperWideProfile(), "items"));
        rows.add(shapeRow(ArrHolder.class, mapperWideProfile(), "arr"));
        rows.add(shapeRow(MapHolder.class, mapperWideProfile(), "byKey"));
        rows.add(shapeRow(OptHolder.class, mapperWideProfile(), "opt"));
        rows.add(shapeRow(EnumHolder.class, mapperWideProfile(), "day"));
        rows.add(shapeRow(PolyHolder.class, mapperWideProfile(), "item"));
        rows.add(shapeRow(PolyListHolder.class, mapperWideProfile(), "items"));
        rows.add(shapeRow(SelfRef.class, mapperWideProfile(), "kids"));
        rows.add(shapeRow(SharedHolder.class, mapperWideProfile(), "one", "two"));
        // Nested plain DTOs under a class-level case-insensitive holder, over a plain mapper.
        rows.add(shapeRow(NestedHolder.class, plainProfile(), "inner"));
        rows.add(shapeRow(SharedNestedHolder.class, plainProfile(), "first", "second"));
        rows.add(shapeRow(ReservedNestedHolder.class, plainProfile(), "nested"));

        rows.add(Arguments.of("a self-referencing list copies as the resolved list schema", (Executable) () -> {
            JsonNode doc = document(generatorFor(mapperWideProfile()), SelfRef.class);
            JsonNode expected = read("{\"items\":{\"$ref\":\"#\"},\"type\":\"array\"}");
            assertAll(
                    () -> assertEquals(expected, doc.at("/properties/kids"), () -> "document: " + doc),
                    () -> assertEquals(expected, foldedCopyOf(doc, "kids"), () -> "document: " + doc));
        }));
        rows.add(Arguments.of(
                "a fragment carrying the folded-copy keyword is refused at construction", (Executable) () -> {
                    String keyword = InputPropertyDescriber.FOLDED_COPY_MARKER;
                    String fragment = "{\"additionalProperties\":false,\"properties\":{\"name\":{\"" + keyword
                            + "\":\"name\",\"type\":\"string\"}},\"type\":\"object\"}";
                    JsonMapperProfile carrying = HardeningFixtures.profile(
                            "folded-copy-keyword-fragment",
                            List.of(JsonSchemaTypeOverride.input(
                                    FragmentTarget.class, JsonSchemaFragment.parse(fragment))));

                    JsonSchemaGenerationException refused = assertThrows(
                            JsonSchemaGenerationException.class,
                            () -> AnnotationJsonSchemaGenerator.forInputProfile(carrying));
                    assertAll(
                            () -> assertTrue(
                                    refused.getMessage().contains("profile 'folded-copy-keyword-fragment'"),
                                    () -> "the refusal names the profile; was: " + refused.getMessage()),
                            () -> assertTrue(
                                    refused.getMessage().contains("\"" + keyword + "\""),
                                    () -> "the refusal names the keyword; was: " + refused.getMessage()));
                }));
        rows.add(Arguments.of("the post-pass replaces a placeholder with its member's schema", (Executable) () -> {
            JsonNode doc = read("{\"type\":\"object\",\"properties\":{\"n\":{\"maxLength\":3,\"type\":\"string\"}},"
                    + "\"patternProperties\":{\"^k$\":{\"" + InputPropertyDescriber.FOLDED_COPY_MARKER
                    + "\":\"n\"}}}");
            InputPropertyDescriber.resolveFoldedCopies(doc);
            List<String> keys = new ArrayList<>();
            collectKeys(doc, keys);
            assertAll(
                    () -> assertEquals(
                            read("{\"maxLength\":3,\"type\":\"string\"}"),
                            doc.path("patternProperties").path("^k$"),
                            () -> "document: " + doc),
                    () -> assertFalse(
                            keys.contains(InputPropertyDescriber.FOLDED_COPY_MARKER),
                            () -> "no placeholder may remain; document: " + doc));
        }));
        rows.add(Arguments.of(
                "the post-pass fails closed for a placeholder whose member is missing", (Executable) () -> {
                    JsonNode doc = read("{\"type\":\"object\",\"properties\":{\"other\":{\"type\":\"string\"}},"
                            + "\"patternProperties\":{\"^k$\":{\"" + InputPropertyDescriber.FOLDED_COPY_MARKER
                            + "\":\"n\"}}}");
                    JsonSchemaGenerationException failure = assertThrows(
                            JsonSchemaGenerationException.class, () -> InputPropertyDescriber.resolveFoldedCopies(doc));
                    assertAll(
                            () -> assertTrue(
                                    failure.getMessage().length() <= Diagnostics.MAX_MESSAGE_LENGTH,
                                    () -> "the failure message is bounded; was: " + failure.getMessage()),
                            () -> assertTrue(
                                    doc.path("patternProperties")
                                            .path("^k$")
                                            .has(InputPropertyDescriber.FOLDED_COPY_MARKER),
                                    () -> "the placeholder is never dropped; document: " + doc));
                }));
        return rows.stream();
    }

    /**
     * One row: every named member of {@code shape} has, under its folded key, a copy equal to its
     * finished {@code properties} value, and the document holds no placeholder keyword.
     */
    private static Arguments shapeRow(Class<?> shape, JsonMapperProfile profile, String... members) {
        return Arguments.of(shape.getSimpleName() + " " + String.join(", ", members), (Executable) () -> {
            JsonNode doc = document(generatorFor(profile), shape);
            List<String> keys = new ArrayList<>();
            collectKeys(doc, keys);
            assertFalse(
                    keys.contains(InputPropertyDescriber.FOLDED_COPY_MARKER),
                    () -> "no placeholder keyword may survive; document: " + doc);
            for (String member : members) {
                JsonNode own = doc.path("properties").path(member);
                JsonNode copy = foldedCopyOf(doc, member);
                assertFalse(own.isMissingNode(), () -> member + " is published; document: " + doc);
                assertNotEquals(0, own.size(), () -> member + " has a non-empty schema; document: " + doc);
                assertEquals(
                        own, copy, () -> "the folded copy of " + member + " is its resolved schema; document: " + doc);
            }
        });
    }
}
