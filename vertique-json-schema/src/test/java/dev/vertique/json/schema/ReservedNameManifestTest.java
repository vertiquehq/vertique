// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Verifies the redaction manifest the input-direction generator binds to every schema it describes.
 *
 * <p>{@link AnnotationJsonSchemaGenerator#describe(java.lang.reflect.Type)} returns the canonical
 * document together with a {@link RedactionManifest}: the sorted RFC 6901 pointers of every
 * reserved-name assertion in the finished document, bound to the SHA-256 digest of that document's
 * canonical bytes. This class proves that a case-sensitive guard and a case-insensitive guard are
 * each listed — the latter separated from the non-ASCII refusal it used to share one pattern with —
 * that every copy case-fold publication or alias expansion makes is listed too, as is every guard
 * under {@code $defs}, {@code items}, or a nested {@code anyOf}, that removing exactly the listed
 * pointers leaves no reserved name in any spelling or fold while the refusal and the published
 * properties remain, that nothing is listed where the generator reserves nothing, that a
 * profile override fragment cannot smuggle the generator's private guard marker in, that the digest
 * matches exactly the schema's canonical bytes, and that {@code describe} and {@code
 * generateCanonical} agree byte for byte and leave no private keyword behind.
 *
 * <p>"Redacted copy" throughout means the parsed document with every listed pointer removed, deepest
 * first, simulating what a publisher that withholds reserved names does with the manifest.
 */
class ReservedNameManifestTest {

    // ---------------------------------------------------------------- shared fixtures

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

    /**
     * A member type with extras described and a hidden member, left unannotated so that the member
     * declaring it decides how it is bound; described inline under a member-level case-insensitive
     * format, it carries its own reserved-name guard for {@code secret}.
     */
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
     * Case-insensitively bound, so every published property's schema is published a second time under
     * {@code patternProperties}, keyed by its case fold; the one member's wire name carries a {@code /}
     * so its pointers need RFC 6901 escaping, and its inline description carries a guard that the
     * case-fold publication therefore copies.
     */
    @JsonFormat(with = JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
    static final class FoldCopiedGuardHolder {

        @JsonProperty("in/out")
        @JsonFormat(with = JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
        public GuardedChild child;
    }

    /**
     * Case-sensitively bound, with one inline-described guarded member that declares an alias
     * spelling, so alias expansion copies the member's guarded schema under that spelling.
     */
    static final class AliasCopiedGuardHolder {

        @JsonAlias("kid")
        @JsonFormat(with = JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
        public GuardedChild child;
    }

    /**
     * A member type with extras described and a hidden member, referenced exactly once — as a list's
     * element type — so its guarded schema is described inline under {@code items}.
     */
    static final class ListedGuardedChild {

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
     * A polymorphic member type, so a member declaring it is described as an {@code anyOf} of its
     * subtypes' schemas, each described inline where it is referenced only once.
     */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = GuardedVariant.class, name = "guarded"),
        @JsonSubTypes.Type(value = PlainVariant.class, name = "plain")
    })
    abstract static class Variant {

        public String label;
    }

    /** The subtype of {@link Variant} with extras described and a hidden member, so it carries a guard. */
    static final class GuardedVariant extends Variant {

        @Schema(hidden = true)
        public String secret;

        private final Map<String, Object> extras = new LinkedHashMap<>();

        @JsonAnySetter
        private void putExtra(String key, Object value) {
            extras.put(key, value);
        }
    }

    /** The subtype of {@link Variant} that reserves nothing. */
    static final class PlainVariant extends Variant {

        public String note;
    }

    /**
     * Case-sensitively bound and closed, with a guarded member type in each nested position the final
     * pass must reach: {@link GuardedChild} referenced twice — directly and through an {@code Optional}
     * — so it is described once under {@code $defs}; a list of {@link ListedGuardedChild}, described
     * inline under {@code items}; and an optional {@link Variant}, whose nullable {@code anyOf} wraps
     * the subtypes' {@code anyOf}.
     */
    static final class NestedGuardHolder {

        public GuardedChild child;

        public Optional<GuardedChild> maybeChild;

        public List<ListedGuardedChild> list;

        public Optional<Variant> maybeVariant;
    }

    /** Case-sensitively bound and closed: no any-setter, so nothing is reserved. */
    static final class PlainDto {

        public String name;
    }

    /** Described only through a profile override fragment that declares its own propertyNames. */
    static final class DeclaredPropertyNamesDto {

        public String name;
    }

    /** The type an override fragment carrying the guard marker is declared for. */
    static final class MarkerFragmentTarget {

        public String name;
    }

    // ---------------------------------------------------------------- expected shapes

    /** The prefix every generator-private keyword is spelled with. */
    private static final String PRIVATE_KEYWORD_PREFIX = "x-vertique-";

    /** The non-ASCII refusal a case-insensitive type always carries, as the pattern's own text. */
    private static final String NON_ASCII_REFUSAL = "[^\\x00-\\x7F]";

    /** The end-of-input anchor every case-fold pattern ends with, as the pattern's own text. */
    private static final String PORTABLE_END_ANCHOR = "(?![\\s\\S])";

    /** The case-sensitive guard over {@link HiddenAndIgnoredDto}'s two reserved names. */
    private static final String ENUM_GUARD = "{\"not\":{\"enum\":[\"ignoredField\",\"secretField\"]}}";

    /** The separated case-insensitive guard over {@link CaseInsensitiveHiddenDto}'s one reserved name. */
    private static final String SEPARATED_GUARD = "{\"allOf\":[{\"not\":{\"pattern\":\"[^\\\\x00-\\\\x7F]\"}},"
            + "{\"not\":{\"pattern\":\"^(?:[sS][eE][cC][rR][eE][tT])(?![\\\\s\\\\S])\"}}]}";

    /** The bare non-ASCII refusal of a case-insensitive type that reserves nothing. */
    private static final String BARE_REFUSAL = "{\"not\":{\"pattern\":\"[^\\\\x00-\\\\x7F]\"}}";

    /** The reserved-name assertion for {@code secret} that each listed pointer of a copied guard names. */
    private static final String SECRET_GUARD =
            "{\"not\":{\"pattern\":\"^(?:[sS][eE][cC][rR][eE][tT])(?![\\\\s\\\\S])\"}}";

    /** The case-sensitive guard over a nested member type's one reserved name, {@code secret}. */
    private static final String SECRET_ENUM_GUARD = "{\"not\":{\"enum\":[\"secret\"]}}";

    /** The propertyNames an override fragment declares for {@link DeclaredPropertyNamesDto}. */
    private static final String DECLARED_PROPERTY_NAMES = "{\"not\":{\"enum\":[\"internalCode\"]}}";

    /** The profile id whose override fragment carries the guard marker at a schema position. */
    private static final String MARKER_AT_SCHEMA_POSITION_PROFILE = "guard-marker-at-schema-position";

    /** The profile id whose override fragment carries the guard marker inside literal data. */
    private static final String MARKER_IN_LITERAL_DATA_PROFILE = "guard-marker-in-literal-data";

    /** The digest's printed form: the algorithm label and 64 lowercase hex characters. */
    private static final Pattern DIGEST_FORM = Pattern.compile("sha-256:[0-9a-f]{64}");

    /** The nesting depth of the over-deep array {@code matches} must reject without throwing. */
    private static final int OVER_DEEP_NESTING = 2_000;

    // ---------------------------------------------------------------- generation helpers

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonMapperProfile profile() {
        return profileNamed("reserved-name-manifest-test");
    }

    private static JsonMapperProfile profileNamed(String id) {
        return JsonMapperProfiles.of(JsonProfileId.of(id), new ObjectMapper());
    }

    private static AnnotationJsonSchemaGenerator inputGenerator() {
        return AnnotationJsonSchemaGenerator.forInputProfile(profile());
    }

    /**
     * A profile declaring one input-direction override for {@link DeclaredPropertyNamesDto}, closed as
     * the profile validation requires of a bean-like type, whose fragment declares its own {@link
     * #DECLARED_PROPERTY_NAMES}.
     */
    private static JsonMapperProfile declaredPropertyNamesProfile() {
        String fragment = "{\"additionalProperties\":false,\"properties\":{\"name\":{\"type\":\"string\"}},"
                + "\"propertyNames\":" + DECLARED_PROPERTY_NAMES + ",\"type\":\"object\"}";
        return HardeningFixtures.profile(
                "declared-property-names",
                List.of(JsonSchemaTypeOverride.input(
                        DeclaredPropertyNamesDto.class, JsonSchemaFragment.parse(fragment))));
    }

    /**
     * A profile declaring one input-direction override for {@link MarkerFragmentTarget}: a closed
     * object whose one property {@code name} is described by {@code nameSchema}.
     *
     * @param id         the profile id, which a refusal must name
     * @param nameSchema the schema of the fragment's {@code name} property, as JSON text
     * @return the profile
     */
    private static JsonMapperProfile markerFragmentProfile(String id, String nameSchema) {
        String fragment =
                "{\"additionalProperties\":false,\"properties\":{\"name\":" + nameSchema + "},\"type\":\"object\"}";
        return HardeningFixtures.profile(
                id,
                List.of(JsonSchemaTypeOverride.input(MarkerFragmentTarget.class, JsonSchemaFragment.parse(fragment))));
    }

    // ---------------------------------------------------------------- document helpers

    private static JsonNode read(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException malformed) {
            throw new AssertionError("expected well-formed JSON", malformed);
        }
    }

    private static String write(JsonNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException unwritable) {
            throw new AssertionError("expected a writable tree", unwritable);
        }
    }

    /** Orders pointers so each is removed before any pointer to one of its ancestors or earlier siblings. */
    private static final Comparator<String> DEEPEST_FIRST = Comparator.comparingLong(
                    (String pointer) -> pointer.chars().filter(c -> c == '/').count())
            .reversed()
            .thenComparing(ReservedNameManifestTest::trailingArrayIndex, Comparator.reverseOrder());

    /** The pointer's last reference token as an array index, or {@code -1} when it is not one. */
    private static int trailingArrayIndex(String pointer) {
        String token = pointer.substring(pointer.lastIndexOf('/') + 1);
        return !token.isEmpty() && token.chars().allMatch(c -> c >= '0' && c <= '9') ? Integer.parseInt(token) : -1;
    }

    /**
     * The redacted copy: {@code described}'s parsed document with every listed pointer removed,
     * deepest first. Each listed pointer must resolve in the document before it is removed.
     *
     * @param described the described schema and its manifest
     * @return the redacted copy
     */
    private static JsonNode redact(CanonicalSchema described) {
        JsonNode copy = read(described.json());
        List<String> ordered = new ArrayList<>(described.redactionManifest().pointers());
        ordered.sort(DEEPEST_FIRST);
        for (String listed : ordered) {
            JsonPointer pointer = JsonPointer.compile(listed);
            assertFalse(pointer.matches(), "a listed pointer never names the whole document");
            assertFalse(
                    copy.at(pointer).isMissingNode(),
                    () -> "listed pointer " + listed + " must resolve in " + described.json());
            JsonNode parent = copy.at(pointer.head());
            JsonPointer last = pointer.last();
            if (parent instanceof ObjectNode object) {
                object.remove(last.getMatchingProperty());
            } else if (parent instanceof ArrayNode array) {
                array.remove(last.getMatchingIndex());
            } else {
                fail("listed pointer " + listed + " has no container parent in " + described.json());
            }
        }
        return copy;
    }

    /**
     * The ASCII case fold of {@code text} as the generator spells it inside a pattern: each ASCII letter
     * becomes a class of its lower- and upper-case form.
     */
    private static String asciiFold(String text) {
        StringBuilder fold = new StringBuilder();
        for (char letter : text.toCharArray()) {
            if (Character.isLetter(letter)) {
                fold.append('[')
                        .append(Character.toLowerCase(letter))
                        .append(Character.toUpperCase(letter))
                        .append(']');
            } else {
                fold.append(letter);
            }
        }
        return fold.toString();
    }

    /**
     * Whether {@code text} carries {@code name} in any spelling: the name itself under a
     * case-insensitive search, or a fragment of its case fold as a pattern would spell it (its first
     * two letters folded, which every longer fold of the name contains).
     */
    private static boolean containsAnySpelling(String text, String name) {
        return text.toLowerCase(Locale.ROOT).contains(name.toLowerCase(Locale.ROOT))
                || text.contains(asciiFold(name.substring(0, 2)));
    }

    /**
     * How many guards for {@code secret} occur in {@code json}: one per occurrence of its full case fold,
     * and one per occurrence of the case-sensitive guard {@link #SECRET_ENUM_GUARD}.
     */
    private static int guardCount(String json) {
        return occurrences(json, asciiFold("secret")) + occurrences(json, SECRET_ENUM_GUARD);
    }

    /** How many non-overlapping times {@code text} occurs in {@code json}. */
    private static int occurrences(String json, String text) {
        int count = 0;
        for (int at = json.indexOf(text); at >= 0; at = json.indexOf(text, at + text.length())) {
            count++;
        }
        return count;
    }

    /** Collects every object key at any depth of {@code node}, literal data included. */
    private static void collectObjectKeys(JsonNode node, Consumer<String> keys) {
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> member : node.properties()) {
                keys.accept(member.getKey());
                collectObjectKeys(member.getValue(), keys);
            }
        } else if (node.isArray()) {
            for (JsonNode element : node) {
                collectObjectKeys(element, keys);
            }
        }
    }

    /**
     * Asserts that no object key anywhere in {@code json} starts with {@link #PRIVATE_KEYWORD_PREFIX},
     * after checking that the guard marker is spelled with that prefix and that the walk reaches the
     * document's keywords at all.
     */
    private static void assertNoPrivateKeyword(String json) {
        assertTrue(
                InputPropertyDescriber.RESERVED_NAME_GUARD_MARKER.startsWith(PRIVATE_KEYWORD_PREFIX),
                "the guard marker must carry the private prefix, or a leftover marker escapes this check");
        List<String> keys = new ArrayList<>();
        collectObjectKeys(read(json), keys::add);
        assertTrue(keys.contains("type"), () -> "the key walk must reach the document's keywords; document: " + json);
        List<String> privateKeys = keys.stream()
                .filter(key -> key.startsWith(PRIVATE_KEYWORD_PREFIX))
                .toList();
        assertEquals(List.of(), privateKeys, () -> "no private keyword may survive; document: " + json);
    }

    /**
     * Asserts that {@code pointer} resolves in {@code document} to exactly {@code expectedGuard}.
     *
     * @param document      the parsed document
     * @param pointer       the listed pointer
     * @param expectedGuard the guard the pointer must name, as JSON text
     */
    private static void assertGuardAt(JsonNode document, String pointer, String expectedGuard) {
        JsonNode resolved = document.at(pointer);
        assertFalse(resolved.isMissingNode(), () -> "pointer " + pointer + " must resolve in " + document);
        assertEquals(read(expectedGuard), resolved, () -> "pointer " + pointer + " must name the guard in " + document);
    }

    /**
     * The non-ASCII refusal left beside a removed case-insensitive guard: the first entry of the same
     * {@code allOf}.
     */
    private static String refusalBeside(JsonNode redacted, String listedPointer) {
        String guardEntry = "/allOf/1";
        assertTrue(
                listedPointer.endsWith(guardEntry),
                () -> "a case-insensitive guard is listed at allOf/1: " + listedPointer);
        String propertyNames = listedPointer.substring(0, listedPointer.length() - guardEntry.length());
        JsonNode refusal = redacted.at(propertyNames + "/allOf/0/not/pattern");
        assertFalse(
                refusal.isMissingNode(),
                () -> "the non-ASCII refusal must remain beside " + listedPointer + " in " + redacted);
        return refusal.asText();
    }

    // ---------------------------------------------------------------- engine helper

    /**
     * Whether vertx-json-schema accepts {@code instance} against {@code schema}, compiled with the REST
     * gate's own options: Draft 2020-12, base URI {@code https://vertique.local/}, {@link
     * OutputFormat#Basic}.
     */
    private static boolean engineAccepts(String schema, JsonObject instance) {
        JsonSchemaOptions options = new JsonSchemaOptions()
                .setDraft(Draft.DRAFT202012)
                .setBaseUri("https://vertique.local/")
                .setOutputFormat(OutputFormat.Basic);
        Validator validator = Validator.create(JsonSchema.of(new JsonObject(schema)), options);
        return Boolean.TRUE.equals(validator.validate(instance).getValid());
    }

    // ---------------------------------------------------------------- the case-sensitive guard

    @Test
    @DisplayName("A case-sensitive guard is listed, and removing it reveals neither hidden name while the engine"
            + " still refuses both")
    void caseSensitiveGuardIsListedAndRedactsBothHiddenNames() {
        AnnotationJsonSchemaGenerator generator = inputGenerator();
        CanonicalSchema described = generator.describe(HiddenAndIgnoredDto.class);
        String json = described.json();
        JsonNode document = read(json);
        assertTrue(containsAnySpelling(json, "secretField"), () -> "the guard must name secretField: " + json);
        assertTrue(containsAnySpelling(json, "ignoredField"), () -> "the guard must name ignoredField: " + json);

        JsonNode redacted = redact(described);
        String redactedText = write(redacted);

        assertAll(
                () -> assertEquals(generator.generateCanonical(HiddenAndIgnoredDto.class), json),
                () -> assertGuardAt(document, "/propertyNames", ENUM_GUARD),
                () -> assertEquals(
                        List.of("/propertyNames"), described.redactionManifest().pointers()),
                () -> assertFalse(containsAnySpelling(redactedText, "secretField"), redactedText),
                () -> assertFalse(containsAnySpelling(redactedText, "ignoredField"), redactedText),
                () -> assertFalse(redacted.at("/properties/name").isMissingNode(), redactedText),
                () -> assertNoPrivateKeyword(json),
                () -> assertFalse(engineAccepts(json, new JsonObject().put("secretField", "x"))),
                () -> assertFalse(engineAccepts(json, new JsonObject().put("ignoredField", "x"))),
                () -> assertTrue(engineAccepts(json, new JsonObject().put("other", "x"))),
                () -> assertTrue(engineAccepts(json, new JsonObject().put("name", "x"))));
    }

    // ---------------------------------------------------------------- the case-insensitive guard

    @Test
    @DisplayName("A case-insensitive guard with a reserved name is separated from the non-ASCII refusal and listed"
            + " alone; without a reserved name the refusal stays bare and nothing is listed")
    void caseInsensitiveGuardIsSeparatedAndListed() {
        assertAll(
                ReservedNameManifestTest::assertReservedNameGuardSeparatedAndListed,
                () -> assertBareRefusalUnlisted(ClosedCaseInsensitiveDto.class),
                () -> assertBareRefusalUnlisted(CaseInsensitiveNothingReservedDto.class));
    }

    /** A case-insensitive type with a reserved name: the separated guard, listed at its second entry. */
    private static void assertReservedNameGuardSeparatedAndListed() {
        AnnotationJsonSchemaGenerator generator = inputGenerator();
        CanonicalSchema described = generator.describe(CaseInsensitiveHiddenDto.class);
        String json = described.json();
        JsonNode document = read(json);
        assertTrue(containsAnySpelling(json, "secret"), () -> "the guard must fold secret: " + json);

        JsonNode redacted = redact(described);
        String redactedText = write(redacted);

        assertAll(
                () -> assertEquals(generator.generateCanonical(CaseInsensitiveHiddenDto.class), json),
                () -> assertGuardAt(document, "/propertyNames", SEPARATED_GUARD),
                () -> assertEquals(
                        List.of("/propertyNames/allOf/1"),
                        described.redactionManifest().pointers()),
                () -> assertFalse(containsAnySpelling(redactedText, "secret"), redactedText),
                () -> assertEquals(NON_ASCII_REFUSAL, refusalBeside(redacted, "/propertyNames/allOf/1")),
                () -> assertFalse(redacted.at("/properties/name").isMissingNode(), redactedText));
    }

    /** A case-insensitive type that reserves nothing: the bare refusal, and an empty manifest. */
    private static void assertBareRefusalUnlisted(Class<?> type) {
        AnnotationJsonSchemaGenerator generator = inputGenerator();
        CanonicalSchema described = generator.describe(type);
        JsonNode document = read(described.json());

        assertAll(
                type.getSimpleName(),
                () -> assertEquals(generator.generateCanonical(type), described.json()),
                () -> assertGuardAt(document, "/propertyNames", BARE_REFUSAL),
                () -> assertEquals(List.of(), described.redactionManifest().pointers()));
    }

    // ---------------------------------------------------------------- copies of a guard

    @ParameterizedTest(name = "{0}")
    @MethodSource("copiedGuards")
    @DisplayName("Every copy of a guard that case-fold publication or alias expansion makes is listed, and so is"
            + " every guard under $defs, items, or a nested anyOf")
    void everyCopyOfAGuardIsListed(
            String label, Class<?> fixture, List<String> expectedPointers, String expectedGuard) {
        CanonicalSchema described = inputGenerator().describe(fixture);
        String json = described.json();
        JsonNode document = read(json);
        int copies = guardCount(json);
        assertTrue(copies >= 2, () -> label + ": the fixture must carry its guard at two or more locations: " + json);
        assertEquals(expectedPointers.size(), copies, () -> label + ": one expected pointer per guard copy: " + json);

        List<String> pointers = described.redactionManifest().pointers();
        JsonNode redacted = redact(described);
        String redactedText = write(redacted);

        assertAll(
                label,
                () -> assertEquals(expectedPointers, pointers),
                () -> assertEquals(pointers.stream().sorted().toList(), pointers, "pointers sort by String.compareTo"),
                () -> assertAll(pointers.stream()
                        .map(pointer -> (Executable) () -> assertGuardAt(document, pointer, expectedGuard))),
                () -> assertFalse(containsAnySpelling(redactedText, "secret"), redactedText),
                () -> assertAll(pointers.stream()
                        .filter(pointer -> SECRET_GUARD.equals(expectedGuard))
                        .map(pointer ->
                                (Executable) () -> assertEquals(NON_ASCII_REFUSAL, refusalBeside(redacted, pointer)))));
    }

    private static Stream<Arguments> copiedGuards() {
        return Stream.of(
                Arguments.of(
                        "a guard copied under patternProperties by case-fold publication",
                        FoldCopiedGuardHolder.class,
                        List.of(
                                "/patternProperties/^[iI][nN]~1[oO][uU][tT](?![\\s\\S])/propertyNames/allOf/1",
                                "/properties/in~1out/propertyNames/allOf/1"),
                        SECRET_GUARD),
                Arguments.of(
                        "a guard copied under an alias spelling by alias expansion",
                        AliasCopiedGuardHolder.class,
                        List.of("/properties/child/propertyNames/allOf/1", "/properties/kid/propertyNames/allOf/1"),
                        SECRET_GUARD),
                Arguments.of(
                        "guards under $defs, items, and a nested anyOf",
                        NestedGuardHolder.class,
                        List.of(
                                "/$defs/GuardedChild/propertyNames",
                                "/properties/list/items/propertyNames",
                                "/properties/maybeVariant/anyOf/1/anyOf/0/propertyNames"),
                        SECRET_ENUM_GUARD));
    }

    // ---------------------------------------------------------------- nothing reserved

    @ParameterizedTest(name = "{0}")
    @MethodSource("generatorsReservingNothing")
    @DisplayName("The manifest is empty where the generator reserves nothing, and a declared propertyNames is never"
            + " listed")
    void manifestIsEmptyWhereNothingIsReserved(
            String label, AnnotationJsonSchemaGenerator generator, Class<?> type, String expectedPropertyNames) {
        CanonicalSchema described = generator.describe(type);
        JsonNode expected = expectedPropertyNames == null ? MissingNode.getInstance() : read(expectedPropertyNames);

        assertAll(
                label,
                () -> assertEquals(List.of(), described.redactionManifest().pointers()),
                () -> assertEquals(generator.generateCanonical(type), described.json()),
                () -> assertTrue(described.redactionManifest().matches(described.json())),
                () -> assertEquals(expected, read(described.json()).path("propertyNames"), described.json()));
    }

    private static Stream<Arguments> generatorsReservingNothing() {
        return Stream.of(
                Arguments.of(
                        "a case-sensitive type with no any-setter, through an input generator",
                        inputGenerator(),
                        PlainDto.class,
                        null),
                Arguments.of(
                        "a type with hidden and ignored names, through an output generator",
                        AnnotationJsonSchemaGenerator.forOutputProfile(profile()),
                        HiddenAndIgnoredDto.class,
                        null),
                Arguments.of(
                        "a type with hidden and ignored names, through the Victools-defaults generator",
                        AnnotationJsonSchemaGenerator.withVictoolsDefaults(),
                        HiddenAndIgnoredDto.class,
                        null),
                Arguments.of(
                        "an override fragment declaring its own propertyNames, through an input generator",
                        AnnotationJsonSchemaGenerator.forInputProfile(declaredPropertyNamesProfile()),
                        DeclaredPropertyNamesDto.class,
                        DECLARED_PROPERTY_NAMES));
    }

    // ---------------------------------------------------------------- the private marker

    @Test
    @DisplayName("An override fragment carrying the guard marker at a schema position is refused, naming the"
            + " profile; one carrying it inside literal data is accepted")
    void overrideFragmentCannotCarryTheGuardMarker() {
        String marker = InputPropertyDescriber.RESERVED_NAME_GUARD_MARKER;
        JsonMapperProfile atSchemaPosition = markerFragmentProfile(
                MARKER_AT_SCHEMA_POSITION_PROFILE, "{\"" + marker + "\":true,\"type\":\"string\"}");
        JsonMapperProfile inLiteralData =
                markerFragmentProfile(MARKER_IN_LITERAL_DATA_PROFILE, "{\"const\":{\"" + marker + "\":true}}");

        JsonSchemaGenerationException refused = assertThrows(
                JsonSchemaGenerationException.class,
                () -> AnnotationJsonSchemaGenerator.forInputProfile(atSchemaPosition),
                "a fragment carrying the guard marker on a schema object must be refused at construction");
        assertAll(
                () -> assertTrue(
                        refused.getMessage().contains("profile '" + MARKER_AT_SCHEMA_POSITION_PROFILE + "'"),
                        () -> "the refusal must name the profile; was: " + refused.getMessage()),
                () -> assertTrue(
                        refused.getMessage().contains("\"" + InputPropertyDescriber.RESERVED_NAME_GUARD_MARKER + "\""),
                        () -> "the refusal must name the guard keyword it found; was: " + refused.getMessage()),
                () -> assertDoesNotThrow(
                        () -> AnnotationJsonSchemaGenerator.forInputProfile(inLiteralData),
                        "literal data is not inspected, so the marker inside a const value is accepted"));
    }

    // ---------------------------------------------------------------- digest and matching

    @Test
    @DisplayName("The manifest matches exactly its schema's canonical bytes, and only this package constructs one")
    void manifestMatchesExactlyItsCanonicalBytes() {
        AnnotationJsonSchemaGenerator generator = inputGenerator();
        CanonicalSchema caseInsensitive = generator.describe(CaseInsensitiveHiddenDto.class);
        CanonicalSchema caseSensitive = generator.describe(HiddenAndIgnoredDto.class);
        RedactionManifest caseInsensitiveManifest = caseInsensitive.redactionManifest();
        RedactionManifest caseSensitiveManifest = caseSensitive.redactionManifest();
        RedactionManifest caseInsensitiveAgain =
                generator.describe(CaseInsensitiveHiddenDto.class).redactionManifest();
        RedactionManifest caseSensitiveAgain =
                generator.describe(HiddenAndIgnoredDto.class).redactionManifest();

        assertAll(
                () -> assertMatchesOnlyItsOwnBytes(caseInsensitive),
                () -> assertMatchesOnlyItsOwnBytes(caseSensitive),
                () -> assertFalse(
                        matchesWithoutThrowing(caseInsensitiveManifest, withExtraAllOfElement(caseInsensitive.json()))),
                () -> assertFalse(matchesWithoutThrowing(
                        caseSensitiveManifest, withoutReservedName(caseSensitive.json(), "secretField"))),
                () -> assertFalse(
                        matchesWithoutThrowing(caseSensitiveManifest, withDuplicatedRootKey(caseSensitive.json()))),
                () -> assertEquals(caseInsensitiveManifest, caseInsensitiveAgain),
                () -> assertEquals(caseInsensitiveManifest.hashCode(), caseInsensitiveAgain.hashCode()),
                () -> assertEquals(caseSensitiveManifest, caseSensitiveAgain),
                () -> assertEquals(caseSensitiveManifest.hashCode(), caseSensitiveAgain.hashCode()),
                () -> assertNotEquals(caseInsensitiveManifest, caseSensitiveManifest),
                () -> assertToStringNamesOnlyLocationsAndDigest(caseInsensitiveManifest, "secret"),
                () -> assertToStringNamesOnlyLocationsAndDigest(caseSensitiveManifest, "secretField", "ignoredField"),
                ReservedNameManifestTest::assertNoPublicOrProtectedConstructor,
                () -> assertThrows(
                        NullPointerException.class, () -> new CanonicalSchema(null, caseInsensitiveManifest)),
                () -> assertThrows(
                        NullPointerException.class, () -> new CanonicalSchema(caseInsensitive.json(), null)));
    }

    /**
     * The variants every manifest must judge alike: its own document, a reordered pretty rendering and
     * trailing whitespace match; trailing content, malformed text, and an over-deep array do not, and
     * none of them throws; {@code null} is rejected.
     */
    private static void assertMatchesOnlyItsOwnBytes(CanonicalSchema described) {
        String json = described.json();
        RedactionManifest manifest = described.redactionManifest();

        assertAll(
                json,
                () -> assertTrue(DIGEST_FORM.matcher(manifest.digest()).matches(), manifest.digest()),
                () -> assertEquals(canonicalDigest(json), manifest.digest()),
                () -> assertTrue(manifest.matches(json)),
                () -> assertTrue(manifest.matches(reordered(json))),
                () -> assertTrue(manifest.matches(json + "  ")),
                () -> assertFalse(matchesWithoutThrowing(manifest, json + "x")),
                () -> assertFalse(matchesWithoutThrowing(manifest, "")),
                () -> assertFalse(matchesWithoutThrowing(manifest, "{")),
                () -> assertFalse(matchesWithoutThrowing(manifest, "[1,")),
                () -> assertFalse(matchesWithoutThrowing(manifest, overDeepArray())),
                () -> assertThrows(NullPointerException.class, () -> manifest.matches(null)));
    }

    private static boolean matchesWithoutThrowing(RedactionManifest manifest, String candidate) {
        return assertDoesNotThrow(() -> manifest.matches(candidate), "matches never throws for content reasons");
    }

    /**
     * The digest recomputed independently: SHA-256 over the UTF-8 bytes of the canonical form of the
     * parsed document.
     */
    private static String canonicalDigest(String json) throws JsonProcessingException, NoSuchAlgorithmException {
        String canonical = SchemaCanonicalizer.canonicalize(NeutralJson.read(json));
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
        return "sha-256:" + HexFormat.of().formatHex(hash);
    }

    /** {@code json} pretty-printed with every object's keys in reverse order. */
    private static String reordered(String json) throws JsonProcessingException {
        String rendering = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(reverseKeys(read(json)));
        assertNotEquals(json, rendering, "the reordered rendering must differ from the document");
        assertTrue(
                rendering.indexOf("\"type\"") < rendering.indexOf("\"$schema\""),
                () -> "the root keys must be reversed: " + rendering);
        return rendering;
    }

    private static JsonNode reverseKeys(JsonNode node) {
        if (node.isObject()) {
            List<Map.Entry<String, JsonNode>> members = new ArrayList<>(node.properties());
            Collections.reverse(members);
            ObjectNode reversed = JsonNodeFactory.instance.objectNode();
            members.forEach(member -> reversed.set(member.getKey(), reverseKeys(member.getValue())));
            return reversed;
        }
        if (node.isArray()) {
            ArrayNode copy = JsonNodeFactory.instance.arrayNode();
            node.forEach(element -> copy.add(reverseKeys(element)));
            return copy;
        }
        return node;
    }

    /** {@code json} with one extra, empty schema appended to the root guard's {@code allOf}. */
    private static String withExtraAllOfElement(String json) {
        JsonNode document = read(json);
        ArrayNode allOf = assertInstanceOf(
                ArrayNode.class,
                document.at("/propertyNames/allOf"),
                () -> "the document must carry an allOf: " + json);
        allOf.addObject();
        String variant = write(document);
        assertNotEquals(json, variant, "the extra allOf element must change the document");
        return variant;
    }

    /** {@code json} with {@code name} removed from the root guard's {@code enum}. */
    private static String withoutReservedName(String json, String name) {
        JsonNode document = read(json);
        ArrayNode names = assertInstanceOf(
                ArrayNode.class,
                document.at("/propertyNames/not/enum"),
                () -> "the document must carry a reserved-name enum: " + json);
        int before = names.size();
        for (int index = names.size() - 1; index >= 0; index--) {
            if (name.equals(names.get(index).asText())) {
                names.remove(index);
            }
        }
        assertEquals(before - 1, names.size(), () -> "the enum must have listed " + name + " once: " + json);
        String variant = write(document);
        assertNotEquals(json, variant, "removing a reserved name must change the document");
        return variant;
    }

    /**
     * {@code json} with its root's {@code type} member written a second time, with the same value,
     * ahead of every other member. A plain tree read keeps one of the two, so the variant reads as the
     * very same document; only a reader that refuses a repeated key tells the two texts apart.
     */
    private static String withDuplicatedRootKey(String json) {
        JsonNode document = read(json);
        JsonNode type = document.get("type");
        assertTrue(type != null && type.isTextual(), () -> "the document must carry a root type: " + json);
        assertTrue(json.startsWith("{\""), () -> "the document must be an object with a member: " + json);
        String variant = "{\"type\":" + write(type) + "," + json.substring(1);
        assertNotEquals(json, variant, "the repeated key must change the text");
        assertEquals(document, read(variant), "a plain read must collapse the repeated key to the same document");
        return variant;
    }

    /** A well-formed array nested {@link #OVER_DEEP_NESTING} levels deep. */
    private static String overDeepArray() {
        String nested = "[".repeat(OVER_DEEP_NESTING) + "]".repeat(OVER_DEEP_NESTING);
        assertEquals(OVER_DEEP_NESTING, nested.chars().filter(c -> c == '[').count(), "the nesting must be built");
        return nested;
    }

    /**
     * {@code toString()} names the digest and every pointer, and neither a reserved name, a fragment of
     * its fold, nor any pattern text.
     */
    private static void assertToStringNamesOnlyLocationsAndDigest(RedactionManifest manifest, String... reserved) {
        String text = manifest.toString();
        assertFalse(manifest.pointers().isEmpty(), "the manifest under test must list a pointer");
        assertAll(
                text,
                () -> assertTrue(text.contains(manifest.digest())),
                () -> assertAll(manifest.pointers().stream()
                        .map(pointer -> (Executable) () -> assertTrue(text.contains(pointer), pointer))),
                () -> assertAll(Stream.of(reserved)
                        .map(name -> (Executable) () -> assertFalse(containsAnySpelling(text, name), name))),
                () -> assertFalse(text.contains(NON_ASCII_REFUSAL)),
                () -> assertFalse(text.contains(PORTABLE_END_ANCHOR)));
    }

    private static void assertNoPublicOrProtectedConstructor() {
        Constructor<?>[] constructors = RedactionManifest.class.getDeclaredConstructors();
        assertTrue(constructors.length > 0, "the manifest declares its constructor");
        for (Constructor<?> constructor : constructors) {
            int modifiers = constructor.getModifiers();
            assertFalse(
                    Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers),
                    () -> "only this package may construct a manifest: " + constructor);
        }
    }

    // ---------------------------------------------------------------- describe and generateCanonical agree

    @ParameterizedTest(name = "{0}")
    @MethodSource("caseFoldingPins")
    @DisplayName("describe and generateCanonical agree byte for byte in either order and leave no private keyword")
    void describeAgreesWithGenerateCanonical(String label, AnnotationJsonSchemaGenerator generator, Class<?> type) {
        CanonicalSchema describedFirst = generator.describe(type);
        String generatedAfterDescribe = generator.generateCanonical(type);
        String generatedFirst = generator.generateCanonical(type);
        CanonicalSchema describedAfterGenerate = generator.describe(type);

        assertAll(
                label,
                () -> assertEquals(generatedAfterDescribe, describedFirst.json(), "describe, then generateCanonical"),
                () -> assertEquals(generatedFirst, describedAfterGenerate.json(), "generateCanonical, then describe"),
                () -> assertTrue(describedFirst.redactionManifest().matches(generatedAfterDescribe)),
                () -> assertTrue(describedAfterGenerate.redactionManifest().matches(generatedFirst)),
                () -> assertNoPrivateKeyword(describedFirst.json()),
                () -> assertNoPrivateKeyword(describedAfterGenerate.json()),
                () -> assertNoPrivateKeyword(generatedAfterDescribe),
                () -> assertNoPrivateKeyword(generatedFirst));
    }

    private static Stream<Arguments> caseFoldingPins() {
        AnnotationJsonSchemaGenerator creatorPinsGenerator =
                AnnotationJsonSchemaGenerator.forInputProfile(profileNamed("test"));
        return Stream.of(
                Arguments.of("a case-sensitive guard", inputGenerator(), HiddenAndIgnoredDto.class),
                Arguments.of(
                        "a case-insensitive guard with a reserved name",
                        inputGenerator(),
                        CaseInsensitiveHiddenDto.class),
                Arguments.of("a closed case-insensitive type", inputGenerator(), ClosedCaseInsensitiveDto.class),
                Arguments.of(
                        "a case-insensitive type reserving nothing",
                        inputGenerator(),
                        CaseInsensitiveNothingReservedDto.class),
                Arguments.of("a guard copied by case-fold publication", inputGenerator(), FoldCopiedGuardHolder.class),
                Arguments.of("a guard copied by alias expansion", inputGenerator(), AliasCopiedGuardHolder.class),
                Arguments.of(
                        "the creator pins' case-insensitive type with a reserved name",
                        creatorPinsGenerator,
                        CreatorAndCaseInsensitivityDescriptionTest.CI2WithReservedName.class),
                Arguments.of(
                        "the Unicode-folding pins' case-insensitive type with extras",
                        AnnotationJsonSchemaGenerator.forInputProfile(profileNamed("ci-unicode-fold-test")),
                        CaseInsensitiveUnicodeFoldingTest.CaseInsensitiveWithExtras.class),
                Arguments.of(
                        "the Unicode-folding pins' closed case-insensitive type",
                        AnnotationJsonSchemaGenerator.forInputProfile(profileNamed("ci-unicode-fold-test")),
                        CaseInsensitiveUnicodeFoldingTest.ClosedCaseInsensitive.class));
    }
}
