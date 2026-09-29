// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import static dev.vertique.json.schema.SchemaAssertions.assertCanonicalForm;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Every case-folding pattern the input generator builds for a case-insensitively bound type ends
 * with the ECMA-262-portable end-of-input form {@code (?![\s\S])} rather than the Java-only
 * {@code \z}: the per-name pattern published under {@code patternProperties}, and the combined
 * reserved-name refusal published under {@code propertyNames.allOf[1].not.pattern} — the entry of
 * its own beside the non-ASCII refusal — both for a single reserved name and for several folded
 * together into one alternation. Because the new form accepts
 * exactly the inputs {@code \z} accepted, the real gate's verdicts over a corpus of trailing line
 * terminators and non-BMP characters are unchanged. Separately, an application-authored {@code
 * @Pattern}, including one that itself contains {@code \z}, is published exactly as written — the
 * generator never rewrites pattern text it did not build itself.
 */
class PortableFoldAnchorTest {

    // ---------------------------------------------------------------- shared fixtures

    /**
     * Bound case-insensitively, with a published property folded from plain letters, a published
     * property whose wire name carries a fold-escaped metacharacter, a bound-but-unpublished (and so
     * reserved) property, and extras described through a method-level any-setter over an
     * unannotated private map.
     */
    @JsonFormat(with = JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
    static final class FoldAnchorDto {

        /** Published under its own ASCII case-folding pattern. */
        @Size(max = 3)
        public String name;

        /** Published under a wire name containing a regex metacharacter the fold must escape. */
        @JsonProperty("a.b")
        @Size(max = 3)
        public String dotted;

        /** Bound by Jackson but never published, so it is reserved. */
        @Schema(hidden = true)
        public String secret;

        private final Map<String, Object> extras = new LinkedHashMap<>();

        /**
         * Collects a property the type does not otherwise declare.
         *
         * @param key   the extra property's key
         * @param value the extra property's value
         */
        @JsonAnySetter
        private void putExtra(String key, Object value) {
            extras.put(key, value);
        }
    }

    /**
     * Bound case-insensitively with two reserved (bound-but-unpublished) names, so the combined
     * fold alternation has more than one alternative.
     */
    @JsonFormat(with = JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
    static final class TwoReservedNamesFoldAnchorDto {

        @Schema(hidden = true)
        public String first;

        @Schema(hidden = true)
        public String second;

        private final Map<String, Object> extras = new LinkedHashMap<>();

        @JsonAnySetter
        private void putExtra(String key, Object value) {
            extras.put(key, value);
        }
    }

    /** A case-sensitive type carrying application-authored patterns, one of which contains {@code \z} itself. */
    static final class ApplicationPatternDto {

        @Pattern(regexp = "^[a-z]+\\z")
        public String zAnchored;

        @Pattern(regexp = "^x$")
        public String dollarAnchored;
    }

    // ---------------------------------------------------------------- expected literals

    private static final String NAME_FOLD_PATTERN = "^(?!name(?![\\s\\S]))[nN][aA][mM][eE](?![\\s\\S])";
    private static final String DOTTED_FOLD_PATTERN = "^(?!a\\.b(?![\\s\\S]))[aA]\\.[bB](?![\\s\\S])";
    private static final String SECRET_REFUSAL_PATTERN = "^(?:[sS][eE][cC][rR][eE][tT])(?![\\s\\S])";
    private static final String TWO_RESERVED_REFUSAL_PATTERN =
            "^(?:[fF][iI][rR][sS][tT]|[sS][eE][cC][oO][nN][dD])(?![\\s\\S])";

    // ---------------------------------------------------------------- generation helpers

    private static JsonMapperProfile plainProfile() {
        return JsonMapperProfiles.of(JsonProfileId.of("portable-fold-anchor-test"), new ObjectMapper());
    }

    private static String canonical(Class<?> type) {
        return AnnotationJsonSchemaGenerator.forInputProfile(plainProfile()).generateCanonical(type);
    }

    /**
     * Generates {@code type}'s canonical schema through the validator-backed input profile, whose
     * {@code @Pattern} rendering runs through {@code MetadataConstraintSource} rather than the
     * no-validator path {@link #canonical(Class)} exercises.
     *
     * @param type      the type to describe
     * @param validator the bean validator backing the profile
     * @return the generated canonical schema document, as text
     */
    private static String canonicalWithValidator(Class<?> type, jakarta.validation.Validator validator) {
        return AnnotationJsonSchemaGenerator.forInputProfile(plainProfile(), validator)
                .generateCanonical(type);
    }

    private static JsonObject canonicalSchema(Class<?> type) {
        return new JsonObject(canonical(type));
    }

    /**
     * Compiles the real gate's validator over a fresh copy of {@code schema}, using the gate's own
     * options: Draft 2020-12, base URI {@code https://vertique.local/}, {@link OutputFormat#Basic}.
     *
     * @param schema the schema to compile; not mutated, since a fresh copy is passed to the validator
     * @return a freshly compiled validator
     */
    private static Validator compileValidator(JsonObject schema) {
        JsonSchemaOptions options = new JsonSchemaOptions()
                .setDraft(Draft.DRAFT202012)
                .setBaseUri("https://vertique.local/")
                .setOutputFormat(OutputFormat.Basic);
        return Validator.create(JsonSchema.of(schema.copy()), options);
    }

    /**
     * Recursively asserts that no {@code pattern} value and no {@code patternProperties} key anywhere
     * in {@code node} carries the legacy {@code \z} anchor.
     *
     * @param node the document node to inspect, recursively
     */
    private static void assertNoLegacyAnchor(JsonNode node) {
        if (node.isObject()) {
            node.fields().forEachRemaining(field -> {
                if ("pattern".equals(field.getKey()) && field.getValue().isTextual()) {
                    String value = field.getValue().asText();
                    assertFalse(
                            value.contains("\\z"),
                            () -> "a 'pattern' value must not carry the legacy \\z anchor; was: " + value);
                }
                if ("patternProperties".equals(field.getKey())
                        && field.getValue().isObject()) {
                    field.getValue()
                            .fieldNames()
                            .forEachRemaining(key -> assertFalse(
                                    key.contains("\\z"),
                                    () -> "a patternProperties key must not carry the legacy \\z anchor; was: " + key));
                }
                assertNoLegacyAnchor(field.getValue());
            });
        } else if (node.isArray()) {
            node.forEach(PortableFoldAnchorTest::assertNoLegacyAnchor);
        }
    }

    // ---------------------------------------------------------------- generated fold text

    @Test
    @DisplayName("Case-folded property patterns and the reserved-name refusal end in the portable end-of-input anchor")
    void foldPatternsEndInThePortableAnchor() {
        JsonNode document = assertCanonicalForm(canonical(FoldAnchorDto.class));

        JsonNode patternProperties = document.path("patternProperties");
        Set<String> patternPropertyKeys = new TreeSet<>();
        patternProperties.fieldNames().forEachRemaining(patternPropertyKeys::add);
        assertEquals(
                Set.of(NAME_FOLD_PATTERN, DOTTED_FOLD_PATTERN),
                patternPropertyKeys,
                () -> "patternProperties keys must be exactly the two portable-anchored folds; document: " + document);

        JsonNode reservedRefusal = document.at("/propertyNames/allOf/1/not/pattern");
        assertEquals(
                SECRET_REFUSAL_PATTERN,
                reservedRefusal.asText(),
                () -> "the single reserved name's refusal must be anchored with the portable form; document: "
                        + document);

        assertNoLegacyAnchor(document);

        // A second fixture with more than one reserved name proves the combined alternation keeps one
        // anchor pair around the whole group, with no anchor on any individual alternative.
        JsonNode twoReservedDocument = assertCanonicalForm(canonical(TwoReservedNamesFoldAnchorDto.class));
        JsonNode combinedRefusal = twoReservedDocument.at("/propertyNames/allOf/1/not/pattern");
        assertEquals(
                TWO_RESERVED_REFUSAL_PATTERN,
                combinedRefusal.asText(),
                () -> "the combined refusal over two reserved names must keep a single leading '^' and a single"
                        + " trailing portable anchor around the whole alternation; document: " + twoReservedDocument);
        assertNoLegacyAnchor(twoReservedDocument);
    }

    // ---------------------------------------------------------------- gate verdicts over the terminator corpus

    /**
     * {@link #foldAnchorSchemaForGateVerdicts()} caches its one generation here, computed lazily on the
     * first call rather than in a static field initializer, so a generation failure fails only this
     * proof's own test invocations instead of the whole class at load time.
     */
    private static JsonObject foldAnchorSchema;

    /**
     * {@code FoldAnchorDto}'s canonical schema, generated once on first use by this proof rather than
     * eagerly at class initialization.
     *
     * @return the cached schema, generating it on the first call
     */
    private static JsonObject foldAnchorSchemaForGateVerdicts() {
        if (foldAnchorSchema == null) {
            foldAnchorSchema = canonicalSchema(FoldAnchorDto.class);
        }
        return foldAnchorSchema;
    }

    /**
     * A Unicode line separator (code point 0x2028) and a non-BMP character (code point 0x1F600, as
     * a surrogate pair), each built from its code point rather than written as a source escape or a
     * literal character: the project's code formatter rewrites such an escape back into a literal,
     * largely invisible character, which this avoids while producing the exact same runtime string.
     */
    private static final String LINE_SEPARATOR = String.valueOf((char) 0x2028);

    private static final String NON_BMP_CHARACTER = new String(Character.toChars(0x1F600));

    @ParameterizedTest(name = "{0}")
    @MethodSource("terminatorAndNonBmpCorpus")
    @DisplayName("Gate verdicts over a corpus of trailing line terminators and non-BMP keys equal their pinned values")
    void gateVerdictsAreUnchangedOnTheTerminatorCorpus(String label, String key, Object value, boolean expectedValid) {
        Validator validator = compileValidator(foldAnchorSchemaForGateVerdicts());
        OutputUnit result = validator.validate(new JsonObject().put(key, value));
        assertEquals(
                expectedValid,
                Boolean.TRUE.equals(result.getValid()),
                () -> label + ": expected valid=" + expectedValid + " for key "
                        + JsonObject.of("key", key).encode());
    }

    private static Stream<Arguments> terminatorAndNonBmpCorpus() {
        return Stream.of(
                Arguments.of("the canonical lowercase spelling exceeds the size bound", "name", "abcd", false),
                Arguments.of("the uppercase fold exceeds the size bound", "NAME", "abcd", false),
                Arguments.of("the uppercase fold within the size bound is valid", "NAME", "abc", true),
                Arguments.of("a trailing LF is not the fold, so an extra", "NAME\n", "abcd", true),
                Arguments.of("a trailing CRLF is not the fold, so an extra", "NAME\r\n", "abcd", true),
                Arguments.of("a trailing CR is not the fold, so an extra", "NAME\r", "abcd", true),
                Arguments.of("a leading extra character breaks the fold, so an extra", "xNAME", "abcd", true),
                Arguments.of("the escaped-dot fold exceeds the size bound", "A.B", "abcd", false),
                Arguments.of("a non-dot character breaks the escaped-dot fold, so an extra", "AxB", "abcd", true),
                Arguments.of("the reserved name refuses the exact spelling", "secret", 1, false),
                Arguments.of("the reserved name refuses the uppercase fold", "SECRET", 1, false),
                Arguments.of("the reserved name refuses a mixed-case fold", "sEcReT", 1, false),
                Arguments.of("a trailing LF is not the reserved fold, so an extra", "SECRET\n", 1, true),
                Arguments.of("a trailing extra character is not the reserved fold, so an extra", "SECRETS", 1, true),
                Arguments.of(
                        "a Unicode line separator anywhere in the key is refused",
                        "NAME" + LINE_SEPARATOR,
                        "abcd",
                        false),
                Arguments.of("a Unicode next-line control anywhere in the key is refused", "NAME\u0085", "abcd", false),
                Arguments.of(
                        "a trailing non-BMP surrogate pair in the key is refused",
                        "NAME" + NON_BMP_CHARACTER,
                        "abcd",
                        false),
                Arguments.of(
                        "a leading non-BMP surrogate pair in the key is refused",
                        NON_BMP_CHARACTER + "name",
                        "abcd",
                        false));
    }

    // ---------------------------------------------------------------- application pattern text

    @Test
    @DisplayName("An application-authored @Pattern, including one that itself contains \\z, is published verbatim")
    void applicationPatternTextIsNeverRewritten() {
        JsonNode document = assertCanonicalForm(canonical(ApplicationPatternDto.class));

        assertEquals(
                "^[a-z]+\\z",
                document.path("properties").path("zAnchored").path("pattern").asText(),
                () -> "a @Pattern already containing \\z must be published byte for byte; document: " + document);
        assertEquals(
                "^x$",
                document.path("properties")
                        .path("dollarAnchored")
                        .path("pattern")
                        .asText(),
                () -> "an ordinary @Pattern must be published byte for byte; document: " + document);

        // The same fixture, generated through the validator-backed input profile instead: @Pattern
        // rendering there runs through MetadataConstraintSource, a different code path from the one
        // above, and it must publish the same text verbatim too.
        JsonNode validatorBackedDocument = assertCanonicalForm(
                canonicalWithValidator(ApplicationPatternDto.class, MetadataTestValidators.plain()));

        assertEquals(
                "^[a-z]+\\z",
                validatorBackedDocument
                        .path("properties")
                        .path("zAnchored")
                        .path("pattern")
                        .asText(),
                () -> "a @Pattern already containing \\z must be published byte for byte through the"
                        + " validator-backed input profile too; document: " + validatorBackedDocument);
        assertEquals(
                "^x$",
                validatorBackedDocument
                        .path("properties")
                        .path("dollarAnchored")
                        .path("pattern")
                        .asText(),
                () -> "an ordinary @Pattern must be published byte for byte through the validator-backed"
                        + " input profile too; document: " + validatorBackedDocument);
    }
}
