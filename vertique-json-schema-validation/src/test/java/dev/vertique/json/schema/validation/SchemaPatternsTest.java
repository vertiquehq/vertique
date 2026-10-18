// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema.validation;

import static org.assertj.core.api.Assertions.assertThat;

import io.vertx.core.json.JsonObject;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Proves the registration-time pattern walk: it reports every regular expression a schema declares that the
 * regex engine cannot compile, by JSON pointer, with the engine's own description and index and never the
 * pattern text, and it never enters literal data.
 */
class SchemaPatternsTest {

    /** An expression that does not compile: an unclosed group. */
    private static final String UNCLOSED_GROUP = "(";

    /** An expression that does not compile: an unclosed character class. */
    private static final String UNCLOSED_CLASS = "[";

    /** A {@code patternProperties} key that does compile, and is distinctive enough to detect a leak. */
    private static final String VALID_KEY_MARKER = "^marker-key-";

    @Test
    @DisplayName("every uncompilable pattern is reported once, a patternProperties key is never named, and"
            + " literal values produce nothing")
    void precompileReportsEveryUncompilablePatternAndRedactsPatternPropertyKeys() {
        // Given a schema with a bad pattern, a bad patternProperties key, a bad pattern reached through $ref,
        // and a bad pattern inside a default value
        JsonObject schema = Schemas.json("{'type':'object','properties':{"
                + "'x':{'type':'string','pattern':'" + UNCLOSED_GROUP + "'},"
                + "'m':{'type':'object','patternProperties':{'" + UNCLOSED_CLASS + "':{}}},"
                + "'r':{'$ref':'#/$defs/Inner'},"
                + "'d':{'type':'object','default':{'pattern':'" + UNCLOSED_GROUP + "'}}"
                + "},'$defs':{'Inner':{'type':'string','pattern':'" + UNCLOSED_GROUP + "'}}}");

        // When the schema is precompiled
        List<PatternCompileFailure> failures = SchemaPatterns.precompile(schema);

        // Then exactly three failures are reported, in document order, none carrying the pattern text
        assertThat(failures)
                .extracting(PatternCompileFailure::pointer)
                .containsExactly("/properties/x/pattern", "/properties/m/patternProperties", "/$defs/Inner/pattern");
        assertThat(failures)
                .extracting(PatternCompileFailure::description)
                .allSatisfy(description -> assertThat(description).doesNotContain("(", "["));
        assertThat(failures)
                .extracting(PatternCompileFailure::index)
                .containsExactly(
                        engineRejectionOf(UNCLOSED_GROUP).getIndex(),
                        engineRejectionOf(UNCLOSED_CLASS).getIndex(),
                        engineRejectionOf(UNCLOSED_GROUP).getIndex());
        assertThat(failures)
                .extracting(PatternCompileFailure::description)
                .containsExactly(
                        engineRejectionOf(UNCLOSED_GROUP).getDescription(),
                        engineRejectionOf(UNCLOSED_CLASS).getDescription(),
                        engineRejectionOf(UNCLOSED_GROUP).getDescription());
    }

    @Test
    @DisplayName("a failure beneath a patternProperties key is reported at the key's bracketed ordinal, never at the"
            + " key itself")
    void aFailureBeneathAPatternPropertiesKeyIsReportedAtTheKeysOrdinal() {
        JsonObject schema = Schemas.json("{'type':'object','properties':{'m':{'type':'object','patternProperties':{"
                + "'" + VALID_KEY_MARKER + "a':{'type':'string','pattern':'" + UNCLOSED_GROUP + "'},"
                + "'" + VALID_KEY_MARKER + "b':{'type':'string'},"
                + "'" + VALID_KEY_MARKER + "c':{'type':'string','pattern':'" + UNCLOSED_CLASS + "'}}}}}");

        List<PatternCompileFailure> failures = SchemaPatterns.precompile(schema);

        assertThat(failures)
                .extracting(PatternCompileFailure::pointer)
                .containsExactly(
                        "/properties/m/patternProperties/[key-0]/pattern",
                        "/properties/m/patternProperties/[key-2]/pattern");
        assertThat(failures.toString()).doesNotContain(VALID_KEY_MARKER);
    }

    @Test
    @DisplayName("every key of a patternProperties object is compiled, in order, before the walk descends")
    void everyPatternPropertiesKeyIsCompiledBeforeTheWalkDescends() {
        JsonObject schema = Schemas.json("{'patternProperties':{"
                + "'" + UNCLOSED_CLASS + "':{'pattern':'" + UNCLOSED_GROUP + "'},"
                + "'" + UNCLOSED_GROUP + "':{}}}");

        List<PatternCompileFailure> failures = SchemaPatterns.precompile(schema);

        assertThat(failures)
                .extracting(PatternCompileFailure::pointer)
                .containsExactly("/patternProperties", "/patternProperties", "/patternProperties/[key-0]/pattern");
        assertThat(failures)
                .extracting(PatternCompileFailure::description)
                .containsExactly(
                        engineRejectionOf(UNCLOSED_CLASS).getDescription(),
                        engineRejectionOf(UNCLOSED_GROUP).getDescription(),
                        engineRejectionOf(UNCLOSED_GROUP).getDescription());
    }

    @Test
    @DisplayName("a schema whose expressions all compile reports nothing")
    void aSchemaWhoseExpressionsAllCompileReportsNothing() {
        JsonObject schema =
                Schemas.json("{'type':'object','properties':{'code':{'type':'string','pattern':'^[a-z]+$'}},"
                        + "'patternProperties':{'^x-':{'type':'string','pattern':'^v'}}}");

        assertThat(SchemaPatterns.precompile(schema)).isEmpty();
    }

    @Test
    @DisplayName("a pointer segment is escaped per RFC 6901")
    void pointerSegmentsAreEscaped() {
        JsonObject schema =
                Schemas.json("{'properties':{'a/b':{'properties':{'c~d':{'pattern':'" + UNCLOSED_GROUP + "'}}}}}");

        assertThat(SchemaPatterns.precompile(schema))
                .extracting(PatternCompileFailure::pointer)
                .containsExactly("/properties/a~1b/properties/c~0d/pattern");
    }

    @Test
    @DisplayName("a pattern inside an array member is reported at its array index")
    void aPatternInsideAnArrayMemberIsReportedAtItsIndex() {
        JsonObject schema = Schemas.json("{'allOf':[{'type':'string'},{'pattern':'" + UNCLOSED_GROUP + "'}],"
                + "'oneOf':[{'type':'object','properties':{'n':{'pattern':'" + UNCLOSED_CLASS + "'}}}]}");

        assertThat(SchemaPatterns.precompile(schema))
                .extracting(PatternCompileFailure::pointer)
                .containsExactly("/allOf/1/pattern", "/oneOf/0/properties/n/pattern");
    }

    @Test
    @DisplayName("the report names the engine's index and description and carries no cause or pattern text")
    void theReportCarriesOnlyTheEnginesDescriptionAndIndex() {
        JsonObject schema = Schemas.json("{'pattern':'a{2,1}'}");
        PatternSyntaxException rejection = engineRejectionOf("a{2,1}");

        List<PatternCompileFailure> failures = SchemaPatterns.precompile(schema);

        assertThat(failures)
                .containsExactly(
                        new PatternCompileFailure("/pattern", rejection.getDescription(), rejection.getIndex()));
        assertThat(failures.get(0).toString()).doesNotContain("a{2,1}");
    }

    @Nested
    @DisplayName("what the walk never enters or compiles")
    class Skipped {

        @ParameterizedTest(name = "inside a {0} value")
        @ValueSource(strings = {"const", "default", "example"})
        @DisplayName("a pattern member inside a literal object value is data and is not compiled")
        void objectLiteralValuesAreSkipped(String literalKeyword) {
            JsonObject schema = Schemas.json("{'" + literalKeyword + "':{'pattern':'" + UNCLOSED_GROUP + "'}}");

            assertThat(SchemaPatterns.precompile(schema)).isEmpty();
        }

        @ParameterizedTest(name = "inside a {0} value")
        @ValueSource(strings = {"enum", "examples"})
        @DisplayName("a pattern member inside a literal array value is data and is not compiled")
        void arrayLiteralValuesAreSkipped(String literalKeyword) {
            JsonObject schema = Schemas.json("{'" + literalKeyword + "':[{'pattern':'" + UNCLOSED_GROUP + "'}]}");

            assertThat(SchemaPatterns.precompile(schema)).isEmpty();
        }

        @Test
        @DisplayName("a property named like a literal keyword is still a schema and is walked")
        void aPropertyNamedLikeALiteralKeywordIsStillWalked() {
            JsonObject schema = Schemas.json("{'properties':{'const':{'pattern':'" + UNCLOSED_GROUP + "'}}}");

            assertThat(SchemaPatterns.precompile(schema))
                    .extracting(PatternCompileFailure::pointer)
                    .containsExactly("/properties/const/pattern");
        }

        @Test
        @DisplayName("a property literally named pattern is a subschema and is not compiled")
        void aPropertyNamedPatternIsNotCompiled() {
            JsonObject schema = Schemas.json("{'type':'object','properties':{'pattern':{'type':'string'}}}");

            assertThat(SchemaPatterns.precompile(schema)).isEmpty();
        }

        @Test
        @DisplayName("a pattern whose value is not a string is not compiled")
        void aNonStringPatternIsNotCompiled() {
            assertThat(SchemaPatterns.precompile(Schemas.json("{'pattern':5}"))).isEmpty();
        }

        @Test
        @DisplayName("the walk leaves the schema it was given untouched")
        void theWalkLeavesItsInputUntouched() {
            JsonObject schema = Schemas.json("{'properties':{'x':{'pattern':'" + UNCLOSED_GROUP + "'}}}");
            JsonObject untouched = schema.copy();

            SchemaPatterns.precompile(schema);

            assertThat(schema).isEqualTo(untouched);
        }
    }

    /**
     * Returns the regex engine's own rejection of {@code expression}.
     *
     * @param expression an expression that does not compile
     * @return the rejection
     */
    private static PatternSyntaxException engineRejectionOf(String expression) {
        try {
            Pattern.compile(expression);
        } catch (PatternSyntaxException rejected) {
            return rejected;
        }
        throw new AssertionError("expected the engine to reject " + expression);
    }
}
