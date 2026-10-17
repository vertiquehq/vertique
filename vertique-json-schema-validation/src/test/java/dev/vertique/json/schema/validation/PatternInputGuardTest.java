// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.json.schema.OutputUnit;
import io.vertx.json.schema.Validator;
import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Proves the pattern-input guard: the per-string bound and the per-validation bound it places ahead of every
 * {@code pattern}, {@code patternProperties} key and bounded format, and the rewrite that puts the bound
 * entries into a private copy of the schema.
 *
 * <p>Each rejection is asserted on the exception type and the limit it carries, never on timing. The one
 * test that uses a catastrophic expression completes only when the guard rejects the input without
 * evaluating that expression, inside a preemptive timeout.
 */
class PatternInputGuardTest {

    @Test
    @DisplayName("a string longer than the per-string bound is rejected, and a value at an unpatterned position"
            + " is accepted")
    void rejectsAStringLongerThanThePerStringBound() {
        // Given a guard bounded at 8 characters per string and a schema with one patterned position
        PatternInputGuard guard = new PatternInputGuard(8, 1_000);
        Validator validator = GuardFixtures.compile(guard, GuardFixtures.schemaWithPatternedAndPlainString("^a+$"));
        JsonObject overlongAtPattern = new JsonObject().put("s", "a".repeat(9));
        JsonObject longAtPlainPosition = new JsonObject().put("t", "a".repeat(9));

        // When a nine-character value reaches the pattern, and again a position without a pattern
        Throwable thrown = catchThrowable(() -> GuardFixtures.validate(guard, validator, overlongAtPattern));
        OutputUnit unpatterned = GuardFixtures.validate(guard, validator, longAtPlainPosition);

        // Then the first is rejected with the limit, and the second is accepted by the same guard
        assertThat(thrown).isInstanceOfSatisfying(PatternInputGuard.PatternInputTooLong.class, rejection -> assertThat(
                        rejection.maxChars())
                .isEqualTo(8));
        assertThat(unpatterned.getValid()).isTrue();
    }

    @Test
    @DisplayName("strings across positions are rejected once their total passes the per-validation bound, and a"
            + " new window starts again from zero")
    void rejectsOnceTheWindowTotalExceedsThePerValidationBound() {
        // Given a guard bounded at 100 per string and 150 per validation, and a schema with two patterns
        PatternInputGuard guard = new PatternInputGuard(100, 150);
        Validator validator = GuardFixtures.compile(guard, GuardFixtures.schemaWithTwoPatterns("^a+$"));
        String eighty = "a".repeat(80);
        JsonObject both = new JsonObject().put("a", eighty).put("b", eighty);

        // When both 80-character strings are validated in one window, and one each in two windows
        Throwable thrown = catchThrowable(() -> GuardFixtures.validate(guard, validator, both));
        OutputUnit first = GuardFixtures.validate(guard, validator, new JsonObject().put("a", eighty));
        OutputUnit second = GuardFixtures.validate(guard, validator, new JsonObject().put("b", eighty));

        // Then the shared window rejects with the total limit, while the separate windows pass
        assertThat(thrown)
                .isInstanceOfSatisfying(
                        PatternInputGuard.PatternInputTotalExceeded.class,
                        rejection -> assertThat(rejection.maxTotalChars()).isEqualTo(150));
        assertThat(first.getValid()).isTrue();
        assertThat(second.getValid()).isTrue();
    }

    @Test
    @DisplayName("the rewrite returns a copy that gains one bound entry per position and never enters literal"
            + " values or a property named like a keyword")
    void rewriteAppendsOneBoundEntryPerPositionAndSkipsLiteralValues() {
        // Given a schema with a pattern, a regex format, a non-empty patternProperties, a property named
        // pattern, and an enum value that itself contains a pattern member
        JsonObject patterned = Schemas.stringWithPattern("^a+$");
        JsonObject regexFormat = Schemas.stringWithFormat("regex");
        JsonObject withPatternProperties = new JsonObject()
                .put("type", "object")
                .put("patternProperties", new JsonObject().put("^x-", new JsonObject().put("type", "string")));
        JsonObject namedPattern = new JsonObject().put("type", "string").put("maxLength", 5);
        JsonObject enumWithPatternMember = new JsonObject()
                .put(
                        "enum",
                        new JsonArray()
                                .add(new JsonObject().put("pattern", "x"))
                                .add("y"));
        JsonObject schema = Schemas.objectWith(
                "p", patterned,
                "r", regexFormat,
                "m", withPatternProperties,
                "pattern", namedPattern,
                "e", enumWithPatternMember);
        JsonObject untouched = schema.copy();

        // When the schema is rewritten
        JsonObject rewritten = PatternInputGuard.rewrite(schema);

        // Then the result is a copy, the input is unchanged, and only the three positions gained entries
        JsonObject expected = Schemas.objectWith(
                "p", Schemas.withAllOf(patterned, Schemas.boundEntry()),
                "r", Schemas.withAllOf(regexFormat, Schemas.boundEntry()),
                "m", Schemas.withAllOf(withPatternProperties, Schemas.propertyNamesEntry()),
                "pattern", namedPattern,
                "e", enumWithPatternMember);
        assertThat(rewritten).isEqualTo(expected).isNotSameAs(schema);
        assertThat(schema).isEqualTo(untouched);
    }

    @Nested
    @DisplayName("the bounds")
    class Bounds {

        @Test
        @DisplayName("the defaults are 4,096 characters per string and 262,144 per validation")
        void defaultsAreTheFrozenNumbers() {
            assertThat(PatternInputGuard.DEFAULT_MAX_CHARS).isEqualTo(4_096);
            assertThat(PatternInputGuard.DEFAULT_MAX_TOTAL_CHARS).isEqualTo(262_144);
            assertThat(PatternInputGuard.BOUND_FORMAT).isEqualTo("x-vertique-pattern-input-bound");
        }

        @Test
        @DisplayName("a string exactly at the per-string bound is accepted")
        void acceptsAStringExactlyAtThePerStringBound() {
            PatternInputGuard guard = new PatternInputGuard(8, 1_000);

            OutputUnit result = GuardFixtures.validate(
                    guard, GuardFixtures.schemaWithPattern("^a+$"), new JsonObject().put("s", "a".repeat(8)));

            assertThat(result.getValid()).isTrue();
        }

        @Test
        @DisplayName("a total exactly at the per-validation bound is accepted and one character more is rejected")
        void acceptsATotalExactlyAtTheBoundAndRejectsOneCharacterMore() {
            JsonObject schema = GuardFixtures.schemaWithTwoPatterns("^a+$");
            JsonObject payload = new JsonObject().put("a", "a".repeat(80)).put("b", "a".repeat(80));

            OutputUnit atTheBound = GuardFixtures.validate(new PatternInputGuard(100, 160), schema, payload);
            Throwable pastTheBound =
                    catchThrowable(() -> GuardFixtures.validate(new PatternInputGuard(100, 159), schema, payload));

            assertThat(atTheBound.getValid()).isTrue();
            assertThat(pastTheBound)
                    .isInstanceOfSatisfying(
                            PatternInputGuard.PatternInputTotalExceeded.class,
                            rejection -> assertThat(rejection.maxTotalChars()).isEqualTo(159));
        }

        @Test
        @DisplayName("outside a window only the per-string bound applies")
        void appliesOnlyThePerStringBoundOutsideAWindow() {
            PatternInputGuard guard = new PatternInputGuard(100, 150);
            Validator validator = GuardFixtures.compile(guard, GuardFixtures.schemaWithTwoPatterns("^a+$"));
            JsonObject both = new JsonObject().put("a", "a".repeat(80)).put("b", "a".repeat(80));

            OutputUnit result = validator.validate(both);

            assertThat(result.getValid()).isTrue();
        }

        @Test
        @DisplayName("opening a second window on the same thread does not reset the running total")
        void openingASecondWindowDoesNotResetTheRunningTotal() {
            PatternInputGuard guard = new PatternInputGuard(100, 150);
            Validator validator = GuardFixtures.compile(guard, GuardFixtures.schemaWithTwoPatterns("^a+$"));
            JsonObject first = new JsonObject().put("a", "a".repeat(80));
            JsonObject second = new JsonObject().put("b", "a".repeat(80));

            try (PatternInputGuard.Window outer = guard.openWindow()) {
                assertThat(validator.validate(first).getValid()).isTrue();
                try (PatternInputGuard.Window inner = guard.openWindow()) {
                    assertThatThrownBy(() -> validator.validate(second))
                            .isInstanceOf(PatternInputGuard.PatternInputTotalExceeded.class);
                }
            }
        }

        @Test
        @DisplayName("an over-long string is rejected without being counted, and the default-sized total passes up"
                + " to its limit")
        void formatValidatorThrowsBothExceptionsAtTheBoundEntry() {
            PatternInputGuard guard = new PatternInputGuard(4_096, 262_144);

            try (PatternInputGuard.Window window = guard.openWindow()) {
                assertThatThrownBy(
                                () -> guard.validateFormat("string", PatternInputGuard.BOUND_FORMAT, "a".repeat(4_097)))
                        .isInstanceOf(PatternInputGuard.PatternInputTooLong.class);
                for (int string = 0; string < 64; string++) {
                    assertThat(guard.validateFormat("string", PatternInputGuard.BOUND_FORMAT, "a".repeat(4_096)))
                            .isNull();
                }
                assertThatThrownBy(() -> guard.validateFormat("string", PatternInputGuard.BOUND_FORMAT, "a"))
                        .isInstanceOf(PatternInputGuard.PatternInputTotalExceeded.class);
            }
        }

        @Test
        @DisplayName("a non-string instance at the bound entry passes without being counted")
        void aNonStringInstancePassesTheBoundEntry() {
            PatternInputGuard guard = new PatternInputGuard(1, 1);

            try (PatternInputGuard.Window window = guard.openWindow()) {
                assertThat(guard.validateFormat("number", PatternInputGuard.BOUND_FORMAT, 123_456_789L))
                        .isNull();
                assertThat(guard.validateFormat("string", PatternInputGuard.BOUND_FORMAT, "a"))
                        .isNull();
            }
        }

        @Test
        @DisplayName("both rejections are stack-trace-free and name only their limit, never the value")
        void rejectionsAreStackTraceFreeAndValueFree() {
            String value = "secret-value-" + "z".repeat(40);
            PatternInputGuard.PatternInputTooLong tooLong = new PatternInputGuard.PatternInputTooLong(8);
            PatternInputGuard.PatternInputTotalExceeded totalExceeded =
                    new PatternInputGuard.PatternInputTotalExceeded(150);
            PatternInputGuard guard = new PatternInputGuard(8, 1_000);
            Throwable thrown = catchThrowable(
                    () -> GuardFixtures.validate(guard, GuardFixtures.schemaWithPattern(".*"), schemaPayload(value)));

            assertThat(tooLong.getStackTrace()).isEmpty();
            assertThat(totalExceeded.getStackTrace()).isEmpty();
            assertThat(tooLong).hasMessage("pattern input exceeds the maximum length of 8 characters");
            assertThat(totalExceeded).hasMessage("pattern input exceeds the maximum total length of 150 characters");
            assertThat(thrown).isInstanceOf(PatternInputGuard.PatternInputTooLong.class);
            assertThat(thrown.getMessage()).doesNotContain("secret-value");
            assertThat(thrown.getStackTrace()).isEmpty();
        }

        @ParameterizedTest(name = "format {0}")
        @ValueSource(strings = {"idn-hostname", "idn-email", "regex"})
        @DisplayName("a string at a bounded format is held to the per-string bound")
        void boundedFormatsAreBoundedToo(String format) {
            PatternInputGuard guard = new PatternInputGuard(8, 1_000);
            JsonObject schema = Schemas.objectWith("s", Schemas.stringWithFormat(format));

            Throwable thrown =
                    catchThrowable(() -> GuardFixtures.validate(guard, schema, schemaPayload("a".repeat(9))));

            assertThat(thrown)
                    .isInstanceOfSatisfying(
                            PatternInputGuard.PatternInputTooLong.class,
                            rejection -> assertThat(rejection.maxChars()).isEqualTo(8));
        }

        @Test
        @DisplayName("a property name longer than the per-string bound is rejected where patternProperties applies")
        void aPropertyNameOverTheBoundIsRejectedUnderPatternProperties() {
            PatternInputGuard guard = new PatternInputGuard(8, 1_000);
            JsonObject schema = Schemas.json("{'type':'object','patternProperties':{'^x':{}}}");

            Throwable thrown = catchThrowable(
                    () -> GuardFixtures.validate(guard, schema, new JsonObject().put("x" + "a".repeat(8), 1)));

            assertThat(thrown)
                    .isInstanceOfSatisfying(
                            PatternInputGuard.PatternInputTooLong.class,
                            rejection -> assertThat(rejection.maxChars()).isEqualTo(8));
        }

        @Test
        @DisplayName("an over-long input is rejected before a catastrophic expression is evaluated")
        void rejectsBeforeACatastrophicExpressionRuns() {
            PatternInputGuard guard = new PatternInputGuard(4_096, 262_144);
            JsonObject schema = GuardFixtures.schemaWithPattern("^(a{1,30}){1,30}$");
            JsonObject payload = schemaPayload("a".repeat(4_096) + "!");

            Throwable thrown = assertTimeoutPreemptively(
                    Duration.ofSeconds(30), () -> catchThrowable(() -> GuardFixtures.validate(guard, schema, payload)));

            assertThat(thrown).isInstanceOf(PatternInputGuard.PatternInputTooLong.class);
        }

        @Test
        @DisplayName("compiling leaves the shared schema untouched and free of the engine's bookkeeping keys")
        void compileLeavesTheSharedSchemaUntouched() {
            JsonObject schema = Schemas.objectWith("s", Schemas.stringWithPattern("^a+$"));
            JsonObject untouched = schema.copy();

            GuardFixtures.compile(new PatternInputGuard(8, 1_000), schema);

            assertThat(schema).isEqualTo(untouched);
            assertThat(schema.encode())
                    .doesNotContain("__absolute_uri__")
                    .doesNotContain(PatternInputGuard.BOUND_FORMAT);
        }

        private JsonObject schemaPayload(String value) {
            return new JsonObject().put("s", value);
        }
    }

    @Nested
    @DisplayName("the rewrite")
    class Rewrite {

        @ParameterizedTest(name = "{0}")
        @MethodSource("shapes")
        @DisplayName("a rewrite shape yields exactly the expected document and leaves its input untouched")
        void rewritesEachShape(Shape shape) {
            JsonObject input = shape.input().copy();

            JsonObject rewritten = PatternInputGuard.rewrite(input);

            assertThat(rewritten).isEqualTo(shape.expected()).isNotSameAs(input);
            assertThat(input).isEqualTo(shape.input());
        }

        @ParameterizedTest(name = "format {0}")
        @ValueSource(
                strings = {
                    "uri",
                    "uri-reference",
                    "url",
                    "uri-template",
                    "json-pointer",
                    "relative-json-pointer",
                    "json-pointer-uri-fragment"
                })
        @DisplayName("each reused format is renamed in place and gains no bound entry")
        void reusedFormatsAreRenamedInPlace(String format) {
            JsonObject input = Schemas.objectWith("s", Schemas.stringWithFormat(format));

            JsonObject rewritten = PatternInputGuard.rewrite(input);

            assertThat(rewritten)
                    .isEqualTo(Schemas.objectWith("s", Schemas.stringWithFormat("x-vertique-format-" + format)));
        }

        @Test
        @DisplayName("a pattern beside a renamed reused format gains the bound entry as well")
        void aPatternBesideAReusedFormatIsRenamedAndBounded() {
            JsonObject input = Schemas.json("{'type':'string','format':'uri','pattern':'^a+$'}");

            JsonObject rewritten = PatternInputGuard.rewrite(input);

            assertThat(rewritten)
                    .isEqualTo(Schemas.jsonWithEntries(
                            "{'type':'string','format':'x-vertique-format-uri','pattern':'^a+$','allOf':[<B>]}"));
        }

        static Stream<Named<Shape>> shapes() {
            return Stream.of(
                    shape(
                            "pattern at the root with no allOf: an allOf is created holding the one entry",
                            "{'type':'string','pattern':'^a+$'}",
                            "{'type':'string','pattern':'^a+$','allOf':[<B>]}"),
                    shape(
                            "pattern beside an existing allOf of two branches: the entry becomes index 2",
                            "{'type':'string','pattern':'^a+$','allOf':[{'minLength':2},{'maxLength':8}]}",
                            "{'type':'string','pattern':'^a+$','allOf':[{'minLength':2},{'maxLength':8},<B>]}"),
                    shape(
                            "pattern under items",
                            "{'type':'array','items':{'type':'string','pattern':'^a+$'}}",
                            "{'type':'array','items':{'type':'string','pattern':'^a+$','allOf':[<B>]}}"),
                    shape(
                            "pattern under $defs, reached through $ref",
                            "{'$ref':'#/$defs/code','$defs':{'code':{'type':'string','pattern':'^a+$'}}}",
                            "{'$ref':'#/$defs/code','$defs':{'code':{'type':'string','pattern':'^a+$',"
                                    + "'allOf':[<B>]}}}"),
                    shape(
                            "pattern under dependentSchemas",
                            "{'type':'object','dependentSchemas':{'a':{'properties':{'b':{'type':'string',"
                                    + "'pattern':'^a+$'}}}}}",
                            "{'type':'object','dependentSchemas':{'a':{'properties':{'b':{'type':'string',"
                                    + "'pattern':'^a+$','allOf':[<B>]}}}}}"),
                    shape(
                            "pattern under each branch of oneOf: one entry per position",
                            "{'oneOf':[{'type':'string','pattern':'^a+$'},{'type':'string','pattern':'^b+$'}]}",
                            "{'oneOf':[{'type':'string','pattern':'^a+$','allOf':[<B>]},"
                                    + "{'type':'string','pattern':'^b+$','allOf':[<B>]}]}"),
                    shape(
                            "pattern under if, then and else",
                            "{'if':{'pattern':'^a'},'then':{'pattern':'^ab'},'else':{'pattern':'^b'}}",
                            "{'if':{'pattern':'^a','allOf':[<B>]},'then':{'pattern':'^ab','allOf':[<B>]},"
                                    + "'else':{'pattern':'^b','allOf':[<B>]}}"),
                    shape(
                            "propertyNames carrying a pattern",
                            "{'type':'object','propertyNames':{'pattern':'^[a-z]+$'}}",
                            "{'type':'object','propertyNames':{'pattern':'^[a-z]+$','allOf':[<B>]}}"),
                    shape(
                            "a non-empty patternProperties gains the propertyNames entry",
                            "{'type':'object','patternProperties':{'^x-':{'type':'string'}}}",
                            "{'type':'object','patternProperties':{'^x-':{'type':'string'}},'allOf':[<P>]}"),
                    shape(
                            "an empty patternProperties is unchanged",
                            "{'type':'object','patternProperties':{}}",
                            "{'type':'object','patternProperties':{}}"),
                    shape(
                            "pattern and a non-empty patternProperties: the pattern entry first",
                            "{'pattern':'^a+$','patternProperties':{'^x-':{'type':'integer'}}}",
                            "{'pattern':'^a+$','patternProperties':{'^x-':{'type':'integer'}},'allOf':[<B>,<P>]}"),
                    shape(
                            "a pattern member inside a const value is unchanged",
                            "{'type':'object','const':{'pattern':'^a+$'}}",
                            "{'type':'object','const':{'pattern':'^a+$'}}"),
                    shape(
                            "a pattern member inside default, examples and example values is unchanged",
                            "{'default':{'pattern':'^a+$'},'examples':[{'pattern':'^a+$'}],'example':{'pattern':'x'}}",
                            "{'default':{'pattern':'^a+$'},'examples':[{'pattern':'^a+$'}],'example':{'pattern':'x'}}"),
                    shape(
                            "a property named const is still a schema and is walked",
                            "{'type':'object','properties':{'const':{'type':'string','pattern':'^a+$'}}}",
                            "{'type':'object','properties':{'const':{'type':'string','pattern':'^a+$',"
                                    + "'allOf':[<B>]}}}"),
                    shape(
                            "properties named pattern and patternProperties: the containers are not schema"
                                    + " nodes, each subschema is walked",
                            "{'type':'object','properties':{'pattern':{'type':'string','pattern':'^a+$'},"
                                    + "'patternProperties':{'type':'object','patternProperties':"
                                    + "{'^x-':{'type':'integer'}}}}}",
                            "{'type':'object','properties':{'pattern':{'type':'string','pattern':'^a+$',"
                                    + "'allOf':[<B>]},'patternProperties':{'type':'object','patternProperties':"
                                    + "{'^x-':{'type':'integer'}},'allOf':[<P>]}}}"),
                    shape("a pattern whose value is not a string is unchanged", "{'pattern':5}", "{'pattern':5}"),
                    shape(
                            "format idn-hostname gains one entry",
                            "{'type':'string','format':'idn-hostname'}",
                            "{'type':'string','format':'idn-hostname','allOf':[<B>]}"),
                    shape(
                            "format idn-email gains one entry",
                            "{'type':'string','format':'idn-email'}",
                            "{'type':'string','format':'idn-email','allOf':[<B>]}"),
                    shape(
                            "format regex beside an existing allOf gains one appended entry",
                            "{'type':'string','format':'regex','allOf':[{'minLength':1}]}",
                            "{'type':'string','format':'regex','allOf':[{'minLength':1},<B>]}"),
                    shape(
                            "a string pattern and format regex gain two entries",
                            "{'type':'string','pattern':'[$]$','format':'regex'}",
                            "{'type':'string','pattern':'[$]$','format':'regex','allOf':[<B>,<B>]}"),
                    shape(
                            "pattern, format idn-email and patternProperties: two entries, then propertyNames",
                            "{'pattern':'@','format':'idn-email','patternProperties':{'^x-':{'type':'integer'}}}",
                            "{'pattern':'@','format':'idn-email','patternProperties':{'^x-':{'type':'integer'}},"
                                    + "'allOf':[<B>,<B>,<P>]}"),
                    shape(
                            "format date-time is unchanged",
                            "{'type':'string','format':'date-time'}",
                            "{'type':'string','format':'date-time'}"),
                    shape(
                            "a format member inside a const value is unchanged",
                            "{'const':{'format':'idn-hostname'}}",
                            "{'const':{'format':'idn-hostname'}}"),
                    shape(
                            "a property literally named format is unchanged",
                            "{'type':'object','properties':{'format':{'type':'string'}}}",
                            "{'type':'object','properties':{'format':{'type':'string'}}}"),
                    shape(
                            "a pattern beside an allOf that is not an array is unchanged",
                            "{'pattern':'^a+$','allOf':{}}",
                            "{'pattern':'^a+$','allOf':{}}"),
                    shape(
                            "a schema with no pattern position equals its input",
                            "{'type':'object','properties':{'n':{'type':'integer','minimum':1}}}",
                            "{'type':'object','properties':{'n':{'type':'integer','minimum':1}}}"));
        }

        private static Named<Shape> shape(String label, String input, String expected) {
            return Named.of(label, new Shape(Schemas.json(input), Schemas.jsonWithEntries(expected)));
        }

        record Shape(JsonObject input, JsonObject expected) {}
    }

    @Nested
    @DisplayName("format validation")
    class FormatValidation {

        @ParameterizedTest(name = "format {0}, value {1}: valid {2}")
        @MethodSource("engineDecidedFormats")
        @DisplayName("a format the guard does not decide itself is still decided by the engine's own check")
        void theEngineStillDecidesTheOtherFormats(String format, String value, boolean valid) {
            PatternInputGuard guard = new PatternInputGuard(8, 1_000);
            JsonObject schema = Schemas.stringWithFormat(format);

            OutputUnit result = GuardFixtures.validate(guard, schema, value);

            assertThat(result.getValid()).isEqualTo(valid);
        }

        static Stream<Arguments> engineDecidedFormats() {
            return Stream.of(
                    Arguments.of("date-time", "2026-09-27T12:00:00Z", true),
                    Arguments.of("date-time", "yesterday", false),
                    Arguments.of("x-app-format", "anything at all", true));
        }

        @Test
        @DisplayName("a null format, an unknown format and an unknown renamed name pass the guard's validator")
        void unknownFormatsPassTheGuardsValidator() {
            PatternInputGuard guard = new PatternInputGuard(8, 1_000);

            assertThat(guard.validateFormat("string", null, "x")).isNull();
            assertThat(guard.validateFormat("string", "x-app-format", "x")).isNull();
            assertThat(guard.validateFormat("string", "x-vertique-format-nonesuch", "x"))
                    .isNull();
        }

        @Test
        @DisplayName("a renamed reused format rejects with a fixed message that names the format and not the value")
        void aRenamedReusedFormatRejectsWithAFixedMessage() {
            PatternInputGuard guard = new PatternInputGuard(8, 1_000);

            assertThat(guard.validateFormat("string", "x-vertique-format-uri", "not a uri"))
                    .isEqualTo("String does not match format \"uri\"");
            assertThat(guard.validateFormat("string", "x-vertique-format-uri", "https://example.com/a"))
                    .isNull();
        }

        @Test
        @DisplayName("a non-string instance passes a renamed reused format")
        void aNonStringInstancePassesARenamedReusedFormat() {
            PatternInputGuard guard = new PatternInputGuard(8, 1_000);

            assertThat(guard.validateFormat("number", "x-vertique-format-uri", 5))
                    .isNull();
        }
    }
}
