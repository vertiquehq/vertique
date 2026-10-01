// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.json.DefaultJsonMapperProfileRegistry;
import io.vertx.core.json.JsonObject;
import io.vertx.json.schema.Draft;
import io.vertx.json.schema.JsonSchema;
import io.vertx.json.schema.JsonSchemaOptions;
import io.vertx.json.schema.Validator;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The measurement design point 4 requires: whether {@code io.vertx.json.schema} 5.1.6's {@code
 * pattern} keyword honors an embedded Java regex modifier group, which decides whether
 * {@link MetadataConstraintSource} may render a flagged {@code @Pattern} inline or must refuse it.
 *
 * <p>{@code io.vertx.json.schema} compiles the {@code pattern} keyword with plain
 * {@code java.util.regex.Pattern.compile(...)} (confirmed here, not assumed): an embedded modifier
 * group such as {@code (?i:...)} is honored exactly as it would be by any direct
 * {@code java.util.regex} caller. {@link MetadataConstraintSource} therefore renders every {@code
 * jakarta.validation.constraints.Pattern.Flag} that has an embeddable Java regex modifier character
 * this way, and refuses (with a diagnostic) the one flag that has none: {@code CANON_EQ}.
 */
class PatternFlagRenderingTest {

    private static JsonMapperProfile vertiqueProfile() {
        return new DefaultJsonMapperProfileRegistry(Set.of()).profile(JsonProfileId.of("vertique"));
    }

    @Test
    @DisplayName("measurement: io.vertx.json.schema honors an embedded (?i:...) modifier group on `pattern`")
    void vertxJsonSchemaHonorsEmbeddedCaseInsensitiveModifier() {
        JsonObject schema = new JsonObject()
                .put("type", "object")
                .put(
                        "properties",
                        new JsonObject()
                                .put(
                                        "code",
                                        new JsonObject().put("type", "string").put("pattern", "(?i:^abc$)")));
        JsonSchemaOptions options =
                new JsonSchemaOptions().setDraft(Draft.DRAFT202012).setBaseUri("https://vertique.local/pattern-flag/");
        Validator validator = Validator.create(JsonSchema.of(schema), options);

        assertTrue(validator.validate(new JsonObject().put("code", "abc")).getValid());
        assertTrue(validator.validate(new JsonObject().put("code", "ABC")).getValid());
        assertTrue(validator.validate(new JsonObject().put("code", "aBc")).getValid());
        assertFalse(validator.validate(new JsonObject().put("code", "xyz")).getValid());
    }

    @Test
    @DisplayName("a CASE_INSENSITIVE @Pattern renders as an embedded (?i:...) group that the real gate enforces")
    void caseInsensitiveFlagRendersAndValidates() {
        Validator validator = generatedValidatorFor(MetadataFixtures.Sharp606Dto.class);

        assertTrue(validator.validate(sharp606Instance("abc")).getValid());
        assertTrue(validator.validate(sharp606Instance("ABC")).getValid());
        assertFalse(validator.validate(sharp606Instance("xyz")).getValid());
    }

    @Test
    @DisplayName("a @Pattern flag with no embeddable modifier (CANON_EQ) is refused with a diagnostic")
    void canonEqFlagIsRefused() {
        jakarta.validation.Validator bv = MetadataTestValidators.plain();
        JsonSchemaGenerationException failure =
                assertThrows(JsonSchemaGenerationException.class, () -> AnnotationJsonSchemaGenerator.forInputProfile(
                                vertiqueProfile(), bv)
                        .generateCanonical(CanonEqDto.class));

        assertTrue(failure.getMessage().contains("CANON_EQ"), "the diagnostic must name the unrenderable flag");
    }

    /** No embeddable Java regex modifier exists for {@code CANON_EQ}. */
    static final class CanonEqDto {
        @Pattern(regexp = "[a-z]+", flags = Pattern.Flag.CANON_EQ)
        public String value;
    }

    @Test
    @DisplayName("a COMMENTS @Pattern whose regexp holds a # comment renders a compilable pattern with the"
            + " validator's own verdicts")
    void commentsFlagWithAHashCommentRendersACompilablePattern() {
        // Given a member constrained by @Pattern(regexp = "^a.b # comment$", flags = {DOTALL, COMMENTS})
        jakarta.validation.Validator bv = MetadataTestValidators.plain();
        String regexp = "^a.b # comment$";
        int flags = java.util.regex.Pattern.DOTALL | java.util.regex.Pattern.COMMENTS;
        List<String> samples = List.of("a\nb", "axb", "ab", "a b");

        // When the validator-backed generator renders it
        String rendered = renderedPattern(CommentedDotallDto.class, "value");

        // Then the rendered pattern compiles as plain java.util.regex (as io.vertx.json.schema compiles it)
        java.util.regex.Pattern published = assertDoesNotThrow(
                () -> java.util.regex.Pattern.compile(rendered),
                () -> "the rendered pattern must compile as plain java.util.regex; rendered: " + rendered);
        java.util.regex.Pattern declared = java.util.regex.Pattern.compile(regexp, flags);
        Validator gate = stringPatternGate(rendered);
        for (String sample : samples) {
            boolean expected = declared.matcher(sample).find();
            assertEquals(
                    expected,
                    published.matcher(sample).find(),
                    () -> "rendered " + rendered + " must decide " + sample + " as the flagged declaration does");
            assertEquals(
                    expected,
                    gate.validate(new JsonObject().put("value", sample)).getValid(),
                    () -> "io.vertx.json.schema must decide " + sample + " as the flagged declaration does");
            assertEquals(
                    bv.validateValue(CommentedDotallDto.class, "value", sample).isEmpty(),
                    gate.validate(new JsonObject().put("value", sample)).getValid(),
                    () -> "io.vertx.json.schema must decide " + sample + " as Hibernate Validator does");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("commentsFlagCases")
    @DisplayName("every COMMENTS @Pattern renders a compilable pattern equal to Pattern.compile(regexp, flags)")
    void commentsFlagRenderingMatchesTheValidatorFlagSemantics(String member, List<String> samples) throws Exception {
        // Given a member whose @Pattern declares the COMMENTS flag
        Pattern annotation = CommentsFlagDto.class.getField(member).getAnnotation(Pattern.class);
        int flags = 0;
        for (Pattern.Flag flag : annotation.flags()) {
            flags |= flag.getValue();
        }
        java.util.regex.Pattern declared = java.util.regex.Pattern.compile(annotation.regexp(), flags);

        // When the validator-backed generator renders it
        String rendered = renderedPattern(CommentsFlagDto.class, member);

        // Then it compiles as plain java.util.regex and decides every sample as the flagged declaration
        java.util.regex.Pattern published = assertDoesNotThrow(
                () -> java.util.regex.Pattern.compile(rendered),
                () -> "the rendered pattern must compile as plain java.util.regex; rendered: " + rendered);
        Validator gate = stringPatternGate(rendered);
        for (String sample : samples) {
            boolean expected = declared.matcher(sample).find();
            assertEquals(
                    expected,
                    published.matcher(sample).find(),
                    () -> "rendered " + rendered + " must decide " + sample + " as the flagged declaration does");
            assertEquals(
                    expected,
                    gate.validate(new JsonObject().put("value", sample)).getValid(),
                    () -> "io.vertx.json.schema must decide " + sample + " as the flagged declaration does");
        }
    }

    static Stream<Arguments> commentsFlagCases() {
        return Stream.of(
                Arguments.of("trailingComment", List.of("a", "ab", "ba", " a")),
                Arguments.of("escapedHashInsideClass", List.of("#x", "x", "##x", "# x")),
                Arguments.of("escapedHash", List.of("a#b", "ab", "a #b", "a\\#b")),
                Arguments.of("trailingBareHash", List.of("ab", "abc", "a b", "xab")),
                Arguments.of("newlineTerminatedComment", List.of("ab", "a b", "a\nb", "acb")),
                Arguments.of("caseInsensitiveComment", List.of("ab", "AB", "aB", "abc", "a b")),
                Arguments.of("commentsTurnedOffInline", List.of("abc", "abc\n")));
    }

    @Test
    @DisplayName("a COMMENTS @Pattern whose regexp does not compile in comments mode fails generation without"
            + " echoing the regexp")
    void commentsFlagWithARegexpInvalidInCommentsModeIsRefused() {
        // Given a member constrained by @Pattern(regexp = "a\\", flags = COMMENTS): a trailing
        // backslash, which Pattern.compile(regexp, COMMENTS) rejects
        String regexp = "a\\";
        assertThrows(
                java.util.regex.PatternSyntaxException.class,
                () -> java.util.regex.Pattern.compile(regexp, java.util.regex.Pattern.COMMENTS),
                "precondition: the declared regexp must not compile with its flags");
        jakarta.validation.Validator bv = MetadataTestValidators.plain();

        // When the validator-backed generator describes the type
        JsonSchemaGenerationException failure =
                assertThrows(JsonSchemaGenerationException.class, () -> AnnotationJsonSchemaGenerator.forInputProfile(
                                vertiqueProfile(), bv)
                        .generateCanonical(TrailingBackslashDto.class));

        // Then the diagnostic names the constrained member and never echoes the regexp
        String message = failure.getMessage();
        assertTrue(
                message.contains("TrailingBackslashDto.value"),
                () -> "the diagnostic must name the constrained member: " + message);
        assertFalse(message.contains(regexp), () -> "the diagnostic echoes the regexp: " + message);
    }

    /** A COMMENTS-mode regexp ending in a lone backslash, which comments mode cannot compile. */
    static final class TrailingBackslashDto {
        @Pattern(regexp = "a\\", flags = Pattern.Flag.COMMENTS)
        public String value;
    }

    @Test
    @DisplayName("a flagged @Pattern without COMMENTS keeps its embedded-modifier rendering byte for byte")
    void flagsWithoutCommentsKeepTheEmbeddedModifierRendering() {
        // Given a member constrained by @Pattern(regexp = "^a.b$", flags = DOTALL)
        // When the validator-backed generator renders it
        String rendered = renderedPattern(DotallOnlyDto.class, "value");

        // Then the rendering is the embedded modifier group around the verbatim regexp
        assertEquals("(?s:^a.b$)", rendered);
    }

    /** A COMMENTS-mode regexp whose {@code #} comment runs to the end of the expression. */
    static final class CommentedDotallDto {
        @Pattern(
                regexp = "^a.b # comment$",
                flags = {Pattern.Flag.DOTALL, Pattern.Flag.COMMENTS})
        public String value;
    }

    /** COMMENTS-mode regexps covering the places a {@code #} can and cannot start a comment. */
    static final class CommentsFlagDto {
        @Pattern(regexp = "^a # trailing comment", flags = Pattern.Flag.COMMENTS)
        public String trailingComment;

        // java.util.regex starts a COMMENTS-mode comment at a bare # even inside a class, so a
        // literal # in a class is escaped.
        @Pattern(regexp = "^[\\#]x$", flags = Pattern.Flag.COMMENTS)
        public String escapedHashInsideClass;

        @Pattern(regexp = "^a\\#b$", flags = Pattern.Flag.COMMENTS)
        public String escapedHash;

        @Pattern(regexp = "^ab$ #", flags = Pattern.Flag.COMMENTS)
        public String trailingBareHash;

        @Pattern(regexp = "^a # c\nb$", flags = Pattern.Flag.COMMENTS)
        public String newlineTerminatedComment;

        @Pattern(
                regexp = "^ab$ # mixed case",
                flags = {Pattern.Flag.COMMENTS, Pattern.Flag.CASE_INSENSITIVE})
        public String caseInsensitiveComment;

        // An inline (?-x) turns comments mode off for the rest of the regexp, so whatever the
        // rendering appends after the regexp is no longer ignorable whitespace.
        @Pattern(regexp = "(?-x)abc", flags = Pattern.Flag.COMMENTS)
        public String commentsTurnedOffInline;
    }

    /** A flagged regexp without COMMENTS, whose rendering must stay as it is. */
    static final class DotallOnlyDto {
        @Pattern(regexp = "^a.b$", flags = Pattern.Flag.DOTALL)
        public String value;
    }

    private static String renderedPattern(Class<?> type, String member) {
        jakarta.validation.Validator bv = MetadataTestValidators.plain();
        JsonObject document = new JsonObject(AnnotationJsonSchemaGenerator.forInputProfile(vertiqueProfile(), bv)
                .generateCanonical(type));
        String rendered =
                document.getJsonObject("properties").getJsonObject(member).getString("pattern");
        assertTrue(rendered != null, () -> "member " + member + " must publish a pattern; document: " + document);
        return rendered;
    }

    /** The real io.vertx.json.schema gate over a single string member carrying the given pattern. */
    private static Validator stringPatternGate(String pattern) {
        JsonObject schema = new JsonObject()
                .put("type", "object")
                .put(
                        "properties",
                        new JsonObject()
                                .put(
                                        "value",
                                        new JsonObject().put("type", "string").put("pattern", pattern)));
        JsonSchemaOptions options =
                new JsonSchemaOptions().setDraft(Draft.DRAFT202012).setBaseUri("https://vertique.local/pattern-flag/");
        return Validator.create(JsonSchema.of(schema), options);
    }

    private static Validator generatedValidatorFor(Class<?> type) {
        jakarta.validation.Validator bv = MetadataTestValidators.plain();
        String canonical = AnnotationJsonSchemaGenerator.forInputProfile(vertiqueProfile(), bv)
                .generateCanonical(type);
        JsonObject schema = new JsonObject(canonical);
        JsonSchemaOptions options =
                new JsonSchemaOptions().setDraft(Draft.DRAFT202012).setBaseUri("https://vertique.local/sharp606/");
        return Validator.create(JsonSchema.of(schema), options);
    }

    private static JsonObject sharp606Instance(String pattern) {
        return new JsonObject()
                .put("range", 15)
                .put("length", "abcd")
                .put("url", "https://example.com")
                .put("caseInsensitivePattern", pattern)
                .put("exclusiveMax", 1);
    }
}
