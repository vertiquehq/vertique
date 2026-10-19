// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assertions.assertAll;

import dev.vertique.core.validation.ViolationDetail;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.json.schema.OutputUnit;
import io.vertx.json.schema.Validator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Proves the violation-detail builder: that it renders a validation result as canonical, value-free
 * {@link ViolationDetail} entries at the engine's instance locations, that every structural keyword is a
 * wrapper, that a result whose errors are all structural or whose keyword cannot be resolved falls back to
 * one value-free detail, and the pointer-form conversion and the pattern-input mapping that surround it.
 *
 * <p>Results come from a real Draft 2020-12 validator wherever the engine produces the shape, and from
 * hand-built {@link OutputUnit}s where it does not.
 */
class ViolationDetailsTest {

    /** A value no detail may ever echo. */
    private static final String SECRET = "SECRET-submitted-value";

    /** A schema that declares {@code amount}, so a reported {@code #/amount} survives the cut-back. */
    private static final JsonObject DECLARES_AMOUNT =
            Schemas.json("{'type':'object','properties':" + "{'amount':{'type':'integer','minimum':2}}}");

    @Test
    @DisplayName("each concrete violation is rendered with its keyword, a canonical message, its constraint and"
            + " the engine's location, and no submitted value")
    void collectRendersKeywordCanonicalMessagesWithEngineLocations() {
        // Given a compiled Draft 2020-12 validator for a schema with a required name and two constraints
        JsonObject schema = Schemas.json("{'type':'object','required':['name'],'properties':{"
                + "'name':{'type':'string','maxLength':3},'n':{'type':'integer','minimum':2}}}");
        Validator validator = Validators.compile(schema);
        OutputUnit violations =
                validator.validate(new JsonObject().put("name", "abcd").put("n", 1));
        OutputUnit missingName = validator.validate(new JsonObject());

        // When both results are collected in the URI-fragment form
        List<ViolationDetail> details = ViolationDetails.collect(violations, schema, false, PathForm.URI_FRAGMENT);
        List<ViolationDetail> root = ViolationDetails.collect(missingName, schema, false, PathForm.URI_FRAGMENT);

        // Then the two constraint violations are canonical and located, and the root required failure names
        // the missing property under the engine's root location
        assertThat(details)
                .containsExactly(
                        new ViolationDetail(
                                "#/name", "must have a maximum length of 3", "maxLength", Map.of("maxLength", 3)),
                        new ViolationDetail("#/n", "must be at least 2", "minimum", Map.of("minimum", 2)));
        assertThat(details.toString()).doesNotContain("abcd");
        assertThat(root).singleElement().satisfies(detail -> {
            assertThat(detail.type()).isEqualTo("required");
            assertThat(detail.path()).isEqualTo("#/name");
            assertThat(detail.message()).contains("name");
            assertThat(detail.args()).isEqualTo(Map.of("required", true));
        });
    }

    @Test
    @DisplayName("every structural keyword is a wrapper, and a result with no concrete error falls back to one"
            + " value-free detail")
    void collectTreatsEveryStructuralKeywordAsAWrapperAndFallsBackValueFree() {
        // Given a closed object rejecting an undeclared key, the false schema, and two hand-built results
        // whose error has a null keyword location and a raw message echoing a secret
        JsonObject closed = Schemas.json("{'type':'object','additionalProperties':false}");
        List<FallbackCase> cases = List.of(
                new FallbackCase(
                        "additionalProperties false",
                        Validators.validate(closed, new JsonObject().put("zz", SECRET)),
                        closed,
                        "#"),
                new FallbackCase(
                        "the false schema",
                        Validators.compileFalseSchema().validate(new JsonObject().put("zz", SECRET)),
                        new JsonObject(),
                        ""),
                new FallbackCase(
                        "a single error with a null keyword location",
                        ResultShapes.singleError(null, "#/amount"),
                        DECLARES_AMOUNT,
                        "#/amount"),
                new FallbackCase(
                        "one of several errors with a null keyword location",
                        ResultShapes.multiError(ResultShapes.leaf(null, "#/amount")),
                        DECLARES_AMOUNT,
                        "#/amount"));

        // When each is collected in the URI-fragment form
        // Then each yields exactly one value-free detail that names no client text
        for (FallbackCase fallback : cases) {
            List<ViolationDetail> details =
                    ViolationDetails.collect(fallback.result(), fallback.schema(), false, PathForm.URI_FRAGMENT);

            assertThat(details)
                    .as(fallback.label())
                    .containsExactly(new ViolationDetail(
                            fallback.expectedPath(), ViolationDetails.VALUE_FREE_MESSAGE, null, null));
            assertThat(details.toString()).as(fallback.label()).doesNotContain("zz", SECRET);
        }

        // And additionalProperties is classified as a structural wrapper with no constraint keyword
        assertThat(SchemaKeywords.isStructuralLocation("#/additionalProperties"))
                .isTrue();
        assertThat(SchemaKeywords.constraintKeyword(ResultShapes.leaf("#/additionalProperties", "#/zz")))
                .isNull();
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
            nullValues = "NULL",
            value = {
                "'#/a%20b/0/a~1b', '/a b/0/a~1b'",
                "'#/%C3%A9', '/é'",
                "'#', ''",
                "'', ''",
                "'/', '/'",
                "'#/', '/'",
                "NULL, ''"
            })
    @DisplayName("a fragment pointer is percent-decoded into an RFC 6901 pointer, the root forms map to empty and"
            + " a lone slash is the member with the empty name")
    void rfc6901DecodesFragmentPointersAndMapsRootToEmpty(String engineLocation, String expected) {
        // Given an engine location, When it is converted, Then the pointer is decoded and root is empty
        assertThat(ViolationDetails.rfc6901(engineLocation)).isEqualTo(expected);
    }

    @Test
    @DisplayName("a member with the empty name keeps its place in a cut-back location, and an undeclared one ends"
            + " the run")
    void emptyNamedMemberKeepsItsPlaceInTheDeclaredLocation() {
        // Given a schema that declares the member "" holding a name, and a schema that reaches the member ""
        // only through additionalProperties while declaring a root name of its own
        JsonObject declared = Schemas.json("{'properties':{'':{'properties':{'name':{'minLength':1}}}}}");
        JsonObject undeclared = Schemas.json("{'properties':{'name':{'type':'string'}},"
                + "'additionalProperties':{'properties':{'name':{'minLength':1}}}}");
        JsonObject body = Schemas.json("{'':{'name':''}}");

        // When each body is validated and collected in the RFC 6901 form
        List<ViolationDetail> throughDeclared =
                ViolationDetails.collect(Validators.validate(declared, body), declared, false, PathForm.RFC6901);
        List<ViolationDetail> throughUndeclared =
                ViolationDetails.collect(Validators.validate(undeclared, body), undeclared, false, PathForm.RFC6901);

        // Then the declared one is located at the empty-named member's name, and the undeclared one stops at the
        // root, never at the different location /name
        assertAll(
                () -> assertThat(throughDeclared)
                        .extracting(ViolationDetail::path, ViolationDetail::type)
                        .containsExactly(tuple("//name", "minLength")),
                () -> assertThat(throughUndeclared)
                        .extracting(ViolationDetail::path, ViolationDetail::type)
                        .containsExactly(tuple("", "minLength")));
    }

    @Test
    @DisplayName("a violation at an undeclared empty-named member stops at the object that holds it")
    void violationAtAnUndeclaredEmptyNamedMemberStopsAtTheObject() {
        // Given a schema that reaches the member "" only through additionalProperties, and a body whose only
        // member is named ""
        JsonObject schema = Schemas.json("{'type':'object','additionalProperties':{'type':'string','minLength':2}}");
        JsonObject body = Schemas.json("{'':'x'}");

        // When the body is validated and collected in the RFC 6901 form
        List<ViolationDetail> details =
                ViolationDetails.collect(Validators.validate(schema, body), schema, false, PathForm.RFC6901);

        // Then the violation is located at the object, never at the client-named location /
        assertThat(details)
                .extracting(ViolationDetail::path, ViolationDetail::type)
                .containsExactly(tuple("", "minLength"));
    }

    @Test
    @DisplayName("guard failures map to typed details with the frozen messages, and any other failure to nothing")
    void patternInputBoundMapsGuardFailuresToTypedDetails() {
        // Given a per-string failure, a per-validation failure wrapped as a cause, and an unrelated failure
        Throwable tooLong = new PatternInputGuard.PatternInputTooLong(8);
        Throwable wrappedTotal = new RuntimeException("wrapper", new PatternInputGuard.PatternInputTotalExceeded(150));
        Throwable unrelated = new IllegalStateException("unrelated");

        // When each is mapped
        Optional<ViolationDetail> length = ViolationDetails.patternInputBound(tooLong);
        Optional<ViolationDetail> total = ViolationDetails.patternInputBound(wrappedTotal);
        Optional<ViolationDetail> none = ViolationDetails.patternInputBound(unrelated);

        // Then the guard failures are typed with their limit and the unrelated one is left unconverted
        assertThat(length)
                .contains(new ViolationDetail(
                        "",
                        "exceeds the maximum length of 8 characters for pattern validation",
                        "patternInputLength",
                        Map.of("limit", 8)));
        assertThat(total)
                .contains(new ViolationDetail(
                        "",
                        "exceeds the maximum total length of 150 characters for pattern validation",
                        "patternInputTotalLength",
                        Map.of("limit", 150)));
        assertThat(none).isEmpty();
    }

    @Nested
    @DisplayName("the frozen constants and the value-free detail")
    class Constants {

        @Test
        @DisplayName("the value-free message and the two pattern-input types are the frozen strings")
        void constantsAreFrozen() {
            assertThat(ViolationDetails.VALUE_FREE_MESSAGE).isEqualTo("does not satisfy the schema");
            assertThat(ViolationDetails.PATTERN_INPUT_LENGTH_TYPE).isEqualTo("patternInputLength");
            assertThat(ViolationDetails.PATTERN_INPUT_TOTAL_LENGTH_TYPE).isEqualTo("patternInputTotalLength");
        }

        @Test
        @DisplayName("valueFree names only the given path, with the frozen message and no type or args")
        void valueFreeNamesOnlyThePath() {
            assertThat(ViolationDetails.valueFree("#/amount"))
                    .isEqualTo(new ViolationDetail("#/amount", "does not satisfy the schema", null, null));
        }

        @Test
        @DisplayName("a bare total-length failure, and a per-string failure wrapped as a cause, are mapped alike")
        void bareAndWrappedGuardFailuresAreMappedAlike() {
            Throwable bareTotal = new PatternInputGuard.PatternInputTotalExceeded(150);
            Throwable wrappedLength = new RuntimeException(new PatternInputGuard.PatternInputTooLong(8));
            Throwable wrappedUnrelated = new RuntimeException(new IllegalStateException("unrelated"));

            assertThat(ViolationDetails.patternInputBound(bareTotal))
                    .get()
                    .extracting(ViolationDetail::type, ViolationDetail::args)
                    .containsExactly("patternInputTotalLength", Map.of("limit", 150));
            assertThat(ViolationDetails.patternInputBound(wrappedLength))
                    .get()
                    .extracting(ViolationDetail::type, ViolationDetail::args)
                    .containsExactly("patternInputLength", Map.of("limit", 8));
            assertThat(ViolationDetails.patternInputBound(wrappedUnrelated)).isEmpty();
        }
    }

    @Nested
    @DisplayName("paths and pointer forms")
    class Paths {

        @Test
        @DisplayName("the RFC 6901 form converts each engine location and maps the root to empty")
        void rfc6901FormConvertsLocations() {
            JsonObject schema = Schemas.json("{'type':'object','required':['name'],'properties':{"
                    + "'name':{'type':'string','maxLength':3},'n':{'type':'integer','minimum':2}}}");
            Validator validator = Validators.compile(schema);

            List<ViolationDetail> violations = ViolationDetails.collect(
                    validator.validate(new JsonObject().put("name", "abcd").put("n", 1)),
                    schema,
                    false,
                    PathForm.RFC6901);
            List<ViolationDetail> root =
                    ViolationDetails.collect(validator.validate(new JsonObject()), schema, false, PathForm.RFC6901);

            assertThat(violations).extracting(ViolationDetail::path).containsExactly("/name", "/n");
            assertThat(root).extracting(ViolationDetail::path).containsExactly("/name");
        }

        @Test
        @DisplayName("a declared name with a space keeps its percent-encoded form, or decodes in RFC 6901 form")
        void aDeclaredNameWithASpaceIsEncodedOrDecoded() {
            JsonObject schema = Schemas.json("{'type':'object','properties':{'a b':{'type':'integer'}}}");
            OutputUnit result = Validators.validate(schema, new JsonObject().put("a b", "x"));

            List<ViolationDetail> fragment = ViolationDetails.collect(result, schema, false, PathForm.URI_FRAGMENT);
            List<ViolationDetail> pointer = ViolationDetails.collect(result, schema, false, PathForm.RFC6901);

            assertThat(fragment)
                    .containsExactly(new ViolationDetail(
                            "#/a%20b", "must be of type: integer", "type", Map.of("type", "integer")));
            assertThat(pointer).extracting(ViolationDetail::path).containsExactly("/a b");
        }

        @Test
        @DisplayName("a root-level violation is located at the engine's root form, or empty in RFC 6901 form")
        void aRootViolationIsLocatedAtTheRoot() {
            JsonObject schema = Schemas.json("{'type':'string'}");
            OutputUnit result = Validators.validate(schema, new JsonObject());

            assertThat(ViolationDetails.collect(result, schema, false, PathForm.URI_FRAGMENT))
                    .containsExactly(
                            new ViolationDetail("#", "must be of type: string", "type", Map.of("type", "string")));
            assertThat(ViolationDetails.collect(result, schema, false, PathForm.RFC6901))
                    .extracting(ViolationDetail::path)
                    .containsExactly("");
        }

        @Test
        @DisplayName("a location reached through a local $ref keeps its declared names")
        void aLocationThroughARefKeepsItsDeclaredNames() {
            JsonObject schema = Schemas.json("{'type':'object','properties':{'pet':{'$ref':'#/$defs/Inner'}},"
                    + "'$defs':{'Inner':{'type':'object','properties':{'name':{'type':'string','maxLength':3}}}}}");
            OutputUnit result = Validators.validate(schema, Schemas.json("{'pet':{'name':'toolong'}}"));

            assertThat(ViolationDetails.collect(result, schema, false, PathForm.URI_FRAGMENT))
                    .containsExactly(new ViolationDetail(
                            "#/pet/name", "must have a maximum length of 3", "maxLength", Map.of("maxLength", 3)));
        }

        @Test
        @DisplayName("a tuple position is kept as its index")
        void aTuplePositionIsKept() {
            JsonObject schema = Schemas.json("{'type':'object','properties':{'t':{'type':'array','prefixItems':"
                    + "[{'type':'string','maxLength':2},{'type':'integer'}]}}}");
            OutputUnit result = Validators.validate(schema, Schemas.json("{'t':['long',1]}"));

            assertThat(ViolationDetails.collect(result, schema, false, PathForm.URI_FRAGMENT))
                    .containsExactly(new ViolationDetail(
                            "#/t/0", "must have a maximum length of 2", "maxLength", Map.of("maxLength", 2)));
        }

        @Test
        @DisplayName("an index under an items schema is kept")
        void anIndexUnderItemsIsKept() {
            JsonObject schema = Schemas.json("{'type':'array','items':{'type':'integer','minimum':2}}");
            OutputUnit result =
                    Validators.compile(schema).validate(new JsonArray().add(1).add(5));

            assertThat(ViolationDetails.collect(result, schema, false, PathForm.URI_FRAGMENT))
                    .containsExactly(new ViolationDetail("#/0", "must be at least 2", "minimum", Map.of("minimum", 2)));
        }

        @Test
        @DisplayName("a violation under a key the client chose is cut back to the containing location and names"
                + " nothing of the key")
        void aClientChosenKeyIsCutBack() {
            String clientKey = "CLIENT-KEY-" + "q".repeat(50);
            JsonObject schema = Schemas.json("{'type':'object','properties':{'name':{'type':'string','maxLength':3}},"
                    + "'additionalProperties':{'type':'string'}}");
            OutputUnit result = Validators.validate(schema, new JsonObject().put(clientKey, 5));

            List<ViolationDetail> details = ViolationDetails.collect(result, schema, false, PathForm.URI_FRAGMENT);

            assertThat(details)
                    .containsExactly(
                            new ViolationDetail("#", "must be of type: string", "type", Map.of("type", "string")));
            assertThat(details.toString()).doesNotContain(clientKey);
        }

        @Test
        @DisplayName("a single-error result with a resolved keyword is located at the root, not at its instance"
                + " location")
        void aSingleErrorWithAResolvedKeywordIsLocatedAtTheRoot() {
            OutputUnit result = ResultShapes.singleError("#/properties/amount/minimum", "#/amount");

            assertThat(ViolationDetails.collect(result, DECLARES_AMOUNT, false, PathForm.URI_FRAGMENT))
                    .containsExactly(new ViolationDetail("", "must be at least 2", "minimum", Map.of("minimum", 2)));
        }

        @Test
        @DisplayName("a valid result yields no detail")
        void aValidResultYieldsNoDetail() {
            OutputUnit result = Validators.validate(DECLARES_AMOUNT, new JsonObject().put("amount", 5));

            assertThat(ViolationDetails.collect(result, DECLARES_AMOUNT, false, PathForm.URI_FRAGMENT))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("detail content")
    class Content {

        @Test
        @DisplayName("a pattern failure names the declared expression, and a type list is rendered as alternatives")
        void patternAndTypeListMessages() {
            JsonObject schema = Schemas.json("{'type':'object','properties':{"
                    + "'s':{'type':'string','pattern':'^a+$'},'u':{'type':['string','null']}}}");
            OutputUnit result = Validators.validate(schema, Schemas.json("{'s':'b','u':5}"));

            assertThat(ViolationDetails.collect(result, schema, false, PathForm.URI_FRAGMENT))
                    .containsExactly(
                            new ViolationDetail(
                                    "#/s", "must match pattern: ^a+$", "pattern", Map.of("pattern", "^a+$")),
                            new ViolationDetail(
                                    "#/u",
                                    "must be of type: string or null",
                                    "type",
                                    Map.of("type", List.of("string", "null"))));
        }

        @Test
        @DisplayName("a required failure keeps the engine's message, which names the property and no value")
        void aRequiredFailureKeepsTheEnginesMessage() {
            JsonObject schema = Schemas.json("{'type':'object','properties':{'p':{'type':'object','required':['x']}}}");
            OutputUnit result = Validators.validate(schema, Schemas.json("{'p':{}}"));
            String raw = result.getErrors().stream()
                    .filter(error -> error.getKeywordLocation().endsWith("/required"))
                    .findFirst()
                    .orElseThrow()
                    .getError();

            assertThat(ViolationDetails.collect(result, schema, false, PathForm.URI_FRAGMENT))
                    .containsExactly(new ViolationDetail("#/p/x", raw, "required", Map.of("required", true)));
            assertThat(raw).contains("x");
        }

        @Test
        @DisplayName("fail-fast stops at the first concrete violation")
        void failFastStopsAtTheFirstConcreteViolation() {
            JsonObject schema = Schemas.json("{'type':'object','properties':{"
                    + "'name':{'type':'string','maxLength':3},'n':{'type':'integer','minimum':2}}}");
            OutputUnit result = Validators.validate(schema, Schemas.json("{'name':'abcd','n':1}"));

            assertThat(ViolationDetails.collect(result, schema, true, PathForm.URI_FRAGMENT))
                    .containsExactly(new ViolationDetail(
                            "#/name", "must have a maximum length of 3", "maxLength", Map.of("maxLength", 3)));
        }
    }

    @Nested
    @DisplayName("the value-free fallback")
    class ValueFreeFallback {

        @ParameterizedTest(name = "keyword location [{0}], single error {1}, fail-fast {2}")
        @MethodSource("unresolvedKeywordShapes")
        @DisplayName("an error whose keyword location is absent or empty yields a value-free detail at its own"
                + " instance location, never the raw message")
        void anUnresolvedKeywordYieldsAValueFreeDetail(String keywordLocation, boolean single, boolean failFast) {
            OutputUnit result = single
                    ? ResultShapes.singleError(keywordLocation, "#/amount")
                    : ResultShapes.multiError(ResultShapes.leaf(keywordLocation, "#/amount"));

            List<ViolationDetail> details =
                    ViolationDetails.collect(result, DECLARES_AMOUNT, failFast, PathForm.URI_FRAGMENT);

            assertThat(details).containsExactly(ViolationDetails.valueFree("#/amount"));
            assertThat(details.toString()).doesNotContain(SECRET);
        }

        static Stream<Arguments> unresolvedKeywordShapes() {
            return Stream.of(
                    Arguments.of(null, true, false),
                    Arguments.of(null, true, true),
                    Arguments.of(null, false, false),
                    Arguments.of(null, false, true),
                    Arguments.of("", true, false),
                    Arguments.of("", true, true),
                    Arguments.of("", false, false),
                    Arguments.of("", false, true));
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(
                strings = {
                    "properties",
                    "additionalProperties",
                    "items",
                    "prefixItems",
                    "allOf",
                    "anyOf",
                    "oneOf",
                    "if",
                    "then",
                    "else",
                    "not",
                    "$ref",
                    "unevaluatedProperties",
                    "unevaluatedItems"
                })
        @DisplayName("an error at a structural keyword is a wrapper: alone it yields one value-free detail")
        void aStructuralKeywordIsAWrapper(String keyword) {
            OutputUnit several = ResultShapes.multiError(ResultShapes.leaf("#/" + keyword, "#/amount"));
            OutputUnit single = ResultShapes.singleError("#/" + keyword, "#/amount");

            assertThat(ViolationDetails.collect(several, DECLARES_AMOUNT, false, PathForm.URI_FRAGMENT))
                    .containsExactly(ViolationDetails.valueFree("#/amount"));
            assertThat(ViolationDetails.collect(single, DECLARES_AMOUNT, false, PathForm.URI_FRAGMENT))
                    .containsExactly(ViolationDetails.valueFree("#/amount"));
        }

        @Test
        @DisplayName("a structural wrapper beside a concrete error is skipped and adds no value-free detail")
        void aStructuralWrapperBesideAConcreteErrorIsSkipped() {
            OutputUnit result = ResultShapes.multiError(
                    ResultShapes.leaf("#/properties", "#/amount"),
                    ResultShapes.leaf("#/properties/amount/minimum", "#/amount"));

            assertThat(ViolationDetails.collect(result, DECLARES_AMOUNT, false, PathForm.URI_FRAGMENT))
                    .containsExactly(
                            new ViolationDetail("#/amount", "must be at least 2", "minimum", Map.of("minimum", 2)));
        }

        @Test
        @DisplayName("an unresolved error before a concrete one stops a fail-fast collection, and is followed by"
                + " the concrete one otherwise")
        void anUnresolvedErrorCountsTowardFailFast() {
            OutputUnit result = ResultShapes.multiError(
                    ResultShapes.leaf(null, "#/amount"), ResultShapes.leaf("#/properties/amount/minimum", "#/amount"));

            assertThat(ViolationDetails.collect(result, DECLARES_AMOUNT, true, PathForm.URI_FRAGMENT))
                    .containsExactly(ViolationDetails.valueFree("#/amount"));
            assertThat(ViolationDetails.collect(result, DECLARES_AMOUNT, false, PathForm.URI_FRAGMENT))
                    .containsExactly(
                            ViolationDetails.valueFree("#/amount"),
                            new ViolationDetail("#/amount", "must be at least 2", "minimum", Map.of("minimum", 2)));
        }

        @Test
        @DisplayName("the fallback of a result with only structural errors names the first error's location, cut back"
                + " to what the schema declares")
        void theFallbackIsCutBackToDeclaredNames() {
            OutputUnit result = ResultShapes.multiError(ResultShapes.leaf("#/additionalProperties", "#/amount/zz"));

            assertThat(ViolationDetails.collect(result, DECLARES_AMOUNT, false, PathForm.URI_FRAGMENT))
                    .containsExactly(ViolationDetails.valueFree("#/amount"));
        }
    }

    @Nested
    @DisplayName("the cut-back bounds")
    class CutBackBounds {

        @Test
        @DisplayName("a short chain of local references keeps the declared names")
        void aShortReferenceChainKeepsTheDeclaredNames() {
            assertThat(pathBeyondAReferenceChain(3)).isEqualTo("#/pet/name");
        }

        @Test
        @DisplayName("a reference chain far past the hop bound ends the run at the containing location")
        void aLongReferenceChainEndsTheRun() {
            assertThat(pathBeyondAReferenceChain(40)).isEqualTo("#/pet");
        }

        @Test
        @DisplayName("a self-referential reference ends the run at the containing location")
        void aSelfReferenceEndsTheRun() {
            JsonObject schema = Schemas.json(
                    "{'properties':{'pet':{'$ref':'#/$defs/loop'}}," + "'$defs':{'loop':{'$ref':'#/$defs/loop'}}}");

            assertThat(collectedPath(schema, "#/pet/name")).isEqualTo("#/pet");
        }

        @ParameterizedTest(name = "{0} subschemas in the composition")
        @CsvSource({"64, '#/pet/name'", "65, '#/pet'"})
        @DisplayName("a composition of up to 64 subschemas keeps the declared names, and a wider one ends the run")
        void theComposedSubschemaBound(int subschemas, String expectedPath) {
            // the pet node itself, the declaring branch, and empty branches make up the total
            JsonArray branches = new JsonArray().add(Schemas.json("{'properties':{'name':{'type':'string'}}}"));
            IntStream.range(0, subschemas - 2).forEach(index -> branches.add(new JsonObject()));
            JsonObject schema = new JsonObject()
                    .put("properties", new JsonObject().put("pet", new JsonObject().put("allOf", branches)));

            assertThat(collectedPath(schema, "#/pet/name")).isEqualTo(expectedPath);
        }

        @ParameterizedTest(name = "segment {0}")
        @CsvSource({
            "0, '#/pet/0'",
            "1234567890, '#/pet/1234567890'",
            "12345678901, '#/pet'",
            "01, '#/pet'",
            "-1, '#/pet'"
        })
        @DisplayName("an array index is named only when it is canonical and at most ten digits")
        void theIndexDigitBound(String segment, String expectedPath) {
            JsonObject schema = Schemas.json("{'properties':{'pet':{'type':'array','items':{'type':'string'}}}}");

            assertThat(collectedPath(schema, "#/pet/" + segment)).isEqualTo(expectedPath);
        }

        @Test
        @DisplayName("a segment that is not a well-formed encoding ends the run")
        void aMalformedEncodingEndsTheRun() {
            JsonObject schema = Schemas.json("{'properties':{'pet':{'properties':{'name':{'type':'string'}}}}}");

            assertThat(collectedPath(schema, "#/pet/na%zzme")).isEqualTo("#/pet");
        }

        private String pathBeyondAReferenceChain(int hops) {
            JsonObject definitions = new JsonObject();
            for (int hop = 0; hop < hops; hop++) {
                definitions.put("h" + hop, new JsonObject().put("$ref", "#/$defs/h" + (hop + 1)));
            }
            definitions.put("h" + hops, Schemas.json("{'properties':{'name':{'type':'string'}}}"));
            JsonObject schema = new JsonObject()
                    .put("properties", new JsonObject().put("pet", new JsonObject().put("$ref", "#/$defs/h0")))
                    .put("$defs", definitions);
            return collectedPath(schema, "#/pet/name");
        }

        /**
         * Collects a hand-built error with an unresolvable keyword at {@code instanceLocation}, which yields the
         * value-free detail at that location after the cut-back, and returns its path.
         */
        private String collectedPath(JsonObject schema, String instanceLocation) {
            OutputUnit result = ResultShapes.singleError(null, instanceLocation);
            return ViolationDetails.collect(result, schema, false, PathForm.URI_FRAGMENT)
                    .get(0)
                    .path();
        }
    }

    @Nested
    @DisplayName("rfc6901 edge forms")
    class Rfc6901EdgeForms {

        // The escaped tilde row is a form the engine never writes (it spells a tilde as ~0), so it is left as written.
        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
            "'/a%20b', '/a b'",
            "'#/a%zzb', '/a%zzb'",
            "'#/a%2', '/a%2'",
            "'#/a~0b', '/a~0b'",
            "'#/a+b', '/a+b'",
            "'#/a%7E1b', '/a%7E1b'",
            "'#/0/1', '/0/1'"
        })
        @DisplayName("invalid escapes and escaped tildes are left as they are, and a plus is not a space")
        void edgeForms(String engineLocation, String expected) {
            assertThat(ViolationDetails.rfc6901(engineLocation)).isEqualTo(expected);
        }

        // An escape of an ASCII letter, digit or slash is a form the engine never writes, so it is left as written.
        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
            "'#/%4A', '/%4A'",
            "'#/%4a', '/%4a'",
            "'#/%c3%A9', '/é'",
            "'#/%C3%a9', '/é'",
            "'#/%2F', '/%2F'",
            "'#/%41%42', '/%41%42'"
        })
        @DisplayName("an escape is recognised with ASCII hex digits of either case: one the engine writes is decoded,"
                + " any other ASCII byte is left as written")
        void asciiHexDigitsOfEitherCaseAreRecognised(String engineLocation, String expected) {
            assertThat(ViolationDetails.rfc6901(engineLocation)).isEqualTo(expected);
        }

        @Test
        @DisplayName("an escape written with full-width digits or letters is not decoded and its text is kept")
        void nonAsciiHexDigitsAreNotDecoded() {
            String fullWidthFour = new String(Character.toChars(0xFF14));
            String fullWidthOne = new String(Character.toChars(0xFF11));
            String fullWidthA = new String(Character.toChars(0xFF21));

            assertThat(ViolationDetails.rfc6901("#/%" + fullWidthFour + fullWidthOne))
                    .isEqualTo("/%" + fullWidthFour + fullWidthOne);
            assertThat(ViolationDetails.rfc6901("#/%" + fullWidthA + "0")).isEqualTo("/%" + fullWidthA + "0");
            assertThat(ViolationDetails.rfc6901("#/%4" + fullWidthOne)).isEqualTo("/%4" + fullWidthOne);
        }

        @Test
        @DisplayName("a multi-byte sequence is decoded as UTF-8")
        void aMultiByteSequenceIsDecodedAsUtf8() {
            String grin = new String(Character.toChars(0x1F600));

            assertThat(ViolationDetails.rfc6901("#/%F0%9F%98%80/x")).isEqualTo("/" + grin + "/x");
        }
    }

    /**
     * The engine writes a character below {@code 0x10} as a percent sign and ONE hex digit, so what follows
     * the escape in a client's key can read as its second digit. An escape that decodes to a character the
     * engine never percent-escapes can therefore not be told from such a key, and is not trusted.
     */
    @Nested
    @DisplayName("percent escapes the engine could not have written faithfully")
    class UnreliableEscapes {

        /** The ASCII characters the engine writes as themselves, or as {@code ~0} and {@code ~1}. */
        private static final String NEVER_PERCENT_ESCAPED = "!#$&'()*+,-./" + "0123456789:;=?@"
                + "ABCDEFGHIJKLMNOPQRSTUVWXYZ" + "_" + "abcdefghijklmnopqrstuvwxyz" + "~";

        private static final JsonObject CLOSED_DECLARING_NAMES =
                Schemas.json("{'properties':{'name':{},'n':{},'P':{}},'additionalProperties':false}");

        private static final JsonObject CLOSED_DECLARING_SLASHES =
                Schemas.json("{'properties':{'/a':{},'/':{}},'additionalProperties':false}");

        private static final JsonObject OPEN_REQUIRING_C = Schemas.json("{'properties':{'name':{'type':'string'}},"
                + "'additionalProperties':{'type':'object','required':['c']}}");

        @ParameterizedTest(name = "key {0}")
        @MethodSource("keysReadAsADeclaredName")
        @DisplayName("a key whose one-digit escape reads as a declared name is cut back as an undeclared one")
        void aKeyReadAsADeclaredNameIsCutBack(String key, String engineLocation) {
            // Given a closed schema declaring the name the key's escape would be misread as
            JsonObject schema = key.startsWith("\u0006") || key.startsWith("\u0005")
                    ? CLOSED_DECLARING_NAMES
                    : CLOSED_DECLARING_SLASHES;

            // When the real engine rejects that key and the result is collected in both forms
            OutputUnit result = Validators.validate(schema, new JsonObject().put(key, 1));
            List<ViolationDetail> pointers = ViolationDetails.collect(result, schema, false, PathForm.RFC6901);
            List<ViolationDetail> fragments = ViolationDetails.collect(result, schema, false, PathForm.URI_FRAGMENT);

            // Then the engine spelled the key as expected, and nothing of it, or of the name it reads as, remains
            assertAll(
                    () -> assertThat(engineLocationOf(key)).isEqualTo(engineLocation),
                    () -> assertThat(pointers).isNotEmpty(),
                    () -> assertThat(pointers).extracting(ViolationDetail::path).containsOnly(""),
                    () -> assertThat(fragments).isNotEmpty(),
                    () -> assertThat(fragments)
                            .extracting(ViolationDetail::path)
                            .containsOnly("#"));
        }

        static Stream<Arguments> keysReadAsADeclaredName() {
            return Stream.of(
                    Arguments.of("\u0006Eame", "#/%6Eame"),
                    Arguments.of("\u0006E", "#/%6E"),
                    Arguments.of("\u00050", "#/%50"),
                    Arguments.of("\u0002Fa", "#/%2Fa"),
                    Arguments.of("\u0007E1", "#/%7E1"));
        }

        @Test
        @DisplayName("a key read as a declared name below a declared parent is cut back to that parent")
        void aKeyReadAsADeclaredNameBelowAParentIsCutBack() {
            JsonObject schema = Schemas.json(
                    "{'properties':{'pet':{'properties':{'name':{}}," + "'additionalProperties':{'type':'object'}}}}");

            OutputUnit result =
                    Validators.validate(schema, new JsonObject().put("pet", new JsonObject().put("\u0006Eame", 1)));

            assertThat(ViolationDetails.collect(result, schema, false, PathForm.RFC6901))
                    .isNotEmpty()
                    .extracting(ViolationDetail::path)
                    .containsOnly("/pet");
        }

        @ParameterizedTest(name = "control character {0}")
        @ValueSource(ints = {2, 3, 4, 5, 6, 7})
        @DisplayName("a control character below 0x10 never makes a key read as another declared name")
        void noControlCharacterBelowSixteenForgesADeclaredName(int control) {
            // Given a closed schema declaring every ASCII character as a one-character name
            JsonObject properties = new JsonObject();
            for (char name = 0; name < 0x80; name++) {
                properties.put(String.valueOf(name), new JsonObject());
            }
            JsonObject schema = new JsonObject().put("properties", properties).put("additionalProperties", false);

            // When the control character is followed by each hex digit, so the engine's escape reads as a byte
            // the engine never percent-escapes
            List<Executable> checks = "0123456789ABCDEFabcdef"
                    .chars()
                    .filter(digit -> NEVER_PERCENT_ESCAPED.indexOf(control * 16 + Character.digit(digit, 16)) >= 0)
                    .mapToObj(digit -> {
                        String key = "" + (char) control + (char) digit;
                        return (Executable) () -> {
                            OutputUnit result = Validators.validate(schema, new JsonObject().put(key, 1));
                            // Then the key is reported as no declared name
                            assertThat(ViolationDetails.collect(result, schema, false, PathForm.RFC6901))
                                    .as("key %s", engineLocationOf(key))
                                    .isNotEmpty()
                                    .extracting(ViolationDetail::path)
                                    .containsOnly("");
                        };
                    })
                    .toList();

            assertThat(checks).isNotEmpty();
            assertAll(checks);
        }

        @Test
        @DisplayName("a key read as a declared name yields no required member appended to a forged parent")
        void aKeyReadAsADeclaredNameDoesNotForgeAParentForARequiredMember() {
            OutputUnit result =
                    Validators.validate(OPEN_REQUIRING_C, new JsonObject().put("\u0006Eame", new JsonObject()));

            List<ViolationDetail> details = ViolationDetails.collect(result, OPEN_REQUIRING_C, false, PathForm.RFC6901);

            assertAll(
                    () -> assertThat(details).extracting(ViolationDetail::type).contains("required"),
                    () -> assertThat(details).extracting(ViolationDetail::path).containsOnly(""));
        }

        @ParameterizedTest(name = "declared name {0}")
        @MethodSource("declaredNamesTheEngineSpellsFaithfully")
        @DisplayName("a declared name the engine spells faithfully is still reported, in either form")
        void aFaithfullySpelledDeclaredNameIsStillReported(String name, String pointer, String fragment) {
            JsonObject schema = new JsonObject()
                    .put("properties", new JsonObject().put(name, new JsonObject().put("type", "integer")));
            OutputUnit result = Validators.validate(schema, new JsonObject().put(name, "x"));

            assertAll(
                    () -> assertThat(ViolationDetails.collect(result, schema, false, PathForm.RFC6901))
                            .extracting(ViolationDetail::path)
                            .containsExactly(pointer),
                    () -> assertThat(ViolationDetails.collect(result, schema, false, PathForm.URI_FRAGMENT))
                            .extracting(ViolationDetail::path)
                            .containsExactly(fragment));
        }

        static Stream<Arguments> declaredNamesTheEngineSpellsFaithfully() {
            return Stream.of(
                    Arguments.of("name", "/name", "#/name"),
                    Arguments.of("a b", "/a b", "#/a%20b"),
                    Arguments.of("é", "/é", "#/%C3%A9"),
                    Arguments.of("a\u001Bb", "/a\u001Bb", "#/a%1Bb"),
                    Arguments.of("a\u0010b", "/a\u0010b", "#/a%10b"),
                    Arguments.of("a%b", "/a%b", "#/a%25b"),
                    Arguments.of("a\"b", "/a\"b", "#/a%22b"),
                    Arguments.of("a/b", "/a~1b", "#/a~1b"),
                    Arguments.of("a~b", "/a~0b", "#/a~0b"),
                    Arguments.of("a b", "/a b", "#/a%E2%80%A8b"));
        }

        @ParameterizedTest(name = "{0} -> {1}")
        @MethodSource("pointersOfEscapesThatCannotBeTrusted")
        @DisplayName("an escape of a character the engine never escapes is kept as written in the RFC 6901 form")
        void anUntrustedEscapeIsKeptAsWritten(String engineLocation, String expected) {
            assertThat(ViolationDetails.rfc6901(engineLocation)).isEqualTo(expected);
        }

        static Stream<Arguments> pointersOfEscapesThatCannotBeTrusted() {
            return Stream.of(
                    Arguments.of("#/%6Eame", "/%6Eame"),
                    Arguments.of("#/%2Fa", "/%2Fa"),
                    Arguments.of("#/%7E1", "/%7E1"),
                    Arguments.of("#/%7e1", "/%7e1"),
                    Arguments.of("#/x/%6Eame/y", "/x/%6Eame/y"),
                    Arguments.of("#/%21", "/%21"),
                    Arguments.of("#/%7E", "/%7E"),
                    Arguments.of("#/%2F", "/%2F"),
                    Arguments.of("#/%41%C3%A9", "/%41\u00e9"),
                    Arguments.of("#/%C3%A9%41", "/\u00e9%41"));
        }

        @Test
        @DisplayName("a declared name followed by a forged ASCII escape is cut back as an undeclared one")
        void aDeclaredNameFollowedByAForgedEscapeIsCutBack() {
            // Given a closed schema declaring a name that ends in a letter, and a key that spells it with a
            // one-digit control escape followed by a digit
            JsonObject schema = Schemas.json("{'properties':{'\u00e9A':{}},'additionalProperties':false}");
            String key = "\u00e9\u00041";

            OutputUnit result = Validators.validate(schema, new JsonObject().put(key, 1));

            // Then the key is no declared name, and the engine's location is the ambiguous one
            assertAll(
                    () -> assertThat(engineLocationOf(key)).isEqualTo("#/%C3%A9%41"),
                    () -> assertThat(ViolationDetails.collect(result, schema, false, PathForm.RFC6901))
                            .isNotEmpty()
                            .extracting(ViolationDetail::path)
                            .containsOnly(""),
                    () -> assertThat(ViolationDetails.collect(result, schema, false, PathForm.URI_FRAGMENT))
                            .isNotEmpty()
                            .extracting(ViolationDetail::path)
                            .containsOnly("#"));
        }

        @ParameterizedTest(name = "key {0}")
        @MethodSource("keysReadAsADeclaredName")
        @DisplayName("the contract form of a real engine location never forges a character from a one-digit escape")
        void theContractFormOfARealLocationForgesNothing(String key, String engineLocation) {
            assertAll(() -> assertThat(engineLocationOf(key)).isEqualTo(engineLocation), () -> assertThat(
                            ViolationDetails.rfc6901(engineLocationOf(key)))
                    .isEqualTo("/" + engineLocation.substring("#/".length())));
        }

        @ParameterizedTest(name = "{0} -> {1}")
        @MethodSource("pointersOfEscapesTheEngineWrites")
        @DisplayName("an escape of a character the engine does escape is decoded as before")
        void anEscapeTheEngineWritesIsDecoded(String engineLocation, String expected) {
            assertThat(ViolationDetails.rfc6901(engineLocation)).isEqualTo(expected);
        }

        static Stream<Arguments> pointersOfEscapesTheEngineWrites() {
            return Stream.of(
                    Arguments.of("#/%C3%A9", "/é"),
                    Arguments.of("#/%E2%80%A8", "/ "),
                    Arguments.of("#/%20", "/ "),
                    Arguments.of("#/a%0Ab", "/a\nb"),
                    Arguments.of("#/a%1Bb", "/a\u001Bb"),
                    Arguments.of("#/a%10b", "/a\u0010b"),
                    Arguments.of("#/%22q%22", "/\"q\""),
                    Arguments.of("#/%25", "/%"),
                    Arguments.of("#/%250A", "/%0A"),
                    Arguments.of("#/%3Cx%3E", "/<x>"),
                    Arguments.of("#/%5B0%5D", "/[0]"),
                    Arguments.of("#/%7Fz", "/\u007Fz"));
        }

        @Test
        @DisplayName("every ASCII escape is kept as written when the engine never writes it, and decoded when it does")
        void everyAsciiEscapeIsDecodedOnlyWhenTheEngineWritesIt() {
            List<Executable> checks = IntStream.range(0, 0x80)
                    .boxed()
                    .flatMap(code -> Stream.of(String.format("%%%02X", code), String.format("%%%02x", code))
                            .map(escape -> {
                                boolean untrusted = NEVER_PERCENT_ESCAPED.indexOf(code) >= 0;
                                String expected = "/x" + (untrusted ? escape : String.valueOf((char) (int) code)) + "y";
                                return (Executable) () -> assertThat(ViolationDetails.rfc6901("#/x" + escape + "y"))
                                        .as("escape %s", escape)
                                        .isEqualTo(expected);
                            }))
                    .toList();

            assertAll(checks);
        }

        @Test
        @DisplayName("an escape written with full-width digits is left as written by the pointer form and ends"
                + " the cut-back at the segment")
        void fullWidthDigitsAreNotAnEscapeInEitherDecoder() {
            // Given a declared name that the full-width digits would spell, if they were read as hex digits
            String escape = "%" + new String(Character.toChars(0xFF14)) + new String(Character.toChars(0xFF11));
            JsonObject schema = Schemas.json("{'properties':{'pet':{'properties':{'A':{}}}}}");
            OutputUnit result = ResultShapes.singleError(null, "#/pet/" + escape);

            // When the location is converted to a pointer and cut back to what the schema declares
            String pointer = ViolationDetails.rfc6901("#/pet/" + escape);
            List<ViolationDetail> pointers = ViolationDetails.collect(result, schema, false, PathForm.RFC6901);
            List<ViolationDetail> fragments = ViolationDetails.collect(result, schema, false, PathForm.URI_FRAGMENT);

            // Then neither decoder read an escape
            assertAll(
                    () -> assertThat(pointer).isEqualTo("/pet/" + escape),
                    () -> assertThat(pointers).extracting(ViolationDetail::path).containsExactly("/pet"),
                    () -> assertThat(fragments)
                            .extracting(ViolationDetail::path)
                            .containsExactly("#/pet"));
        }

        @ParameterizedTest(name = "full-width escape of {1}")
        @MethodSource("fullWidthEscapesOfEscapedBytes")
        @DisplayName("an escape written with full-width digits that would read as a byte the engine escapes is not"
                + " read as that name")
        void fullWidthDigitsOfAnEscapedByteAreNotReadAsThatName(String escape, String declaredName) {
            // Given a closed schema declaring the one-character name the full-width digits would spell, if they
            // were read as hex digits
            JsonObject schema = new JsonObject()
                    .put("properties", new JsonObject().put(declaredName, new JsonObject()))
                    .put("additionalProperties", false);
            OutputUnit result = ResultShapes.singleError(null, "#/" + escape);

            // When the location is converted to a pointer and cut back to what the schema declares
            String pointer = ViolationDetails.rfc6901("#/" + escape);
            List<ViolationDetail> pointers = ViolationDetails.collect(result, schema, false, PathForm.RFC6901);
            List<ViolationDetail> fragments = ViolationDetails.collect(result, schema, false, PathForm.URI_FRAGMENT);

            // Then the text is kept as written by the pointer form, and the cut-back ends before the segment
            assertAll(
                    () -> assertThat(pointer).isEqualTo("/" + escape),
                    () -> assertThat(pointers).extracting(ViolationDetail::path).containsExactly(""),
                    () -> assertThat(fragments)
                            .extracting(ViolationDetail::path)
                            .containsExactly("#"));
        }

        static Stream<Arguments> fullWidthEscapesOfEscapedBytes() {
            String fullWidthTwo = new String(Character.toChars(0xFF12));
            String fullWidthZero = new String(Character.toChars(0xFF10));
            return Stream.of(
                    Arguments.of("%" + fullWidthTwo + fullWidthZero, " "),
                    Arguments.of("%" + fullWidthTwo + fullWidthTwo, "\""));
        }

        /** The engine's own instance location for a body whose only member has the given key, under a closed schema. */
        private String engineLocationOf(String key) {
            OutputUnit result =
                    Validators.validate(Schemas.json("{'additionalProperties':false}"), new JsonObject().put(key, 1));
            return result.getErrors() == null || result.getErrors().isEmpty()
                    ? result.getInstanceLocation()
                    : result.getErrors().get(0).getInstanceLocation();
        }
    }

    @Nested
    @DisplayName("safeDetail")
    class SafeDetail {

        @ParameterizedTest(name = "{0} {1}")
        @MethodSource("messages")
        @DisplayName("a keyword with its constraint is rendered canonically, and the raw engine message is used"
                + " only where it is reviewed to be value-free")
        void rendersTheCanonicalMessage(String keyword, Map<String, Object> args, String rawMessage, String expected) {
            assertThat(ViolationDetails.safeDetail(keyword, args, rawMessage)).isEqualTo(expected);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"minLength", "maxLength", "minimum", "pattern", "type", "format", "const", "enum"})
        @DisplayName("a keyword whose constraint is missing from the args never returns the engine's message")
        void aMissingConstraintNeverReturnsTheEnginesMessage(String keyword) {
            String sentinel = "SENTINEL-" + SECRET;

            String detail = ViolationDetails.safeDetail(keyword, Map.of(), "the value " + sentinel + " is bad");

            assertThat(detail).isEqualTo(keyword + " constraint violated").doesNotContain(sentinel);
        }

        @Test
        @DisplayName("a null keyword yields the fixed message and never the engine's message")
        void aNullKeywordYieldsTheFixedMessage() {
            String sentinel = "SENTINEL-SECRET-submitted-value";

            String detail = ViolationDetails.safeDetail(null, Map.of(), "the value " + sentinel + " is bad");

            assertThat(detail).isEqualTo("is invalid").doesNotContain(sentinel);
        }

        static Stream<Arguments> messages() {
            String raw = "500 is greater than 100";
            return Stream.of(
                    Arguments.of("minLength", Map.of("minLength", 3), raw, "must have a minimum length of 3"),
                    Arguments.of("maxLength", Map.of("maxLength", 3), raw, "must have a maximum length of 3"),
                    Arguments.of("minimum", Map.of("minimum", 2), raw, "must be at least 2"),
                    Arguments.of("maximum", Map.of("maximum", 9), raw, "must be at most 9"),
                    Arguments.of("exclusiveMinimum", Map.of("exclusiveMinimum", 1), raw, "must be greater than 1"),
                    Arguments.of("exclusiveMaximum", Map.of("exclusiveMaximum", 10), raw, "must be less than 10"),
                    Arguments.of("minItems", Map.of("minItems", 1), raw, "must have at least 1 items"),
                    Arguments.of("maxItems", Map.of("maxItems", 5), raw, "must have at most 5 items"),
                    Arguments.of("minProperties", Map.of("minProperties", 1), raw, "must have at least 1 properties"),
                    Arguments.of("maxProperties", Map.of("maxProperties", 4), raw, "must have at most 4 properties"),
                    Arguments.of("multipleOf", Map.of("multipleOf", 5), raw, "must be a multiple of 5"),
                    Arguments.of("pattern", Map.of("pattern", "^a+$"), raw, "must match pattern: ^a+$"),
                    Arguments.of("required", Map.of("required", true), "missing \"x\"", "missing \"x\""),
                    Arguments.of("required", Map.of("required", true), null, "is missing a required field"),
                    Arguments.of("type", Map.of("type", "string"), raw, "must be of type: string"),
                    Arguments.of(
                            "type", Map.of("type", List.of("string", "null")), raw, "must be of type: string or null"),
                    Arguments.of(
                            "type",
                            Map.of("type", List.of("string", "integer", "null")),
                            raw,
                            "must be of type: string, integer or null"),
                    Arguments.of("type", Map.of("type", true), raw, "must be of the required type"),
                    Arguments.of("type", Map.of("type", List.of()), raw, "must be of the required type"),
                    Arguments.of(
                            "propertyNames",
                            Map.of("propertyNames", true),
                            raw,
                            "contains a property name the schema does not allow"),
                    Arguments.of(
                            "patternProperties", Map.of("patternProperties", true), raw, "does not satisfy the schema"),
                    Arguments.of("format", Map.of("format", true), raw, "format constraint violated"),
                    Arguments.of("const", Map.of("const", true), raw, "const constraint violated"),
                    Arguments.of("minLength", Map.of(), "raw message", "minLength constraint violated"),
                    Arguments.of("pattern", Map.of("other", 1), raw, "pattern constraint violated"),
                    Arguments.of("type", Map.of(), raw, "type constraint violated"),
                    Arguments.of("format", Map.of(), raw, "format constraint violated"),
                    Arguments.of("minLength", Map.of(), null, "minLength constraint violated"),
                    Arguments.of(null, Map.of(), null, "is invalid"));
        }
    }

    /** The engine's message for an omitted required member, up to the opening quote of the name. */
    private static final String MISSING_PREFIX = "Instance does not have required property \"";

    /** A schema whose {@code pet} must have a {@code name}; the engine reports the omission at the parent. */
    private static final JsonObject PET_REQUIRES_NAME =
            Schemas.json("{'properties':{'pet':{'type':'object','required':['name']}}}");

    /** The keyword location and instance location of the unit the engine emits for {@link #PET_REQUIRES_NAME}. */
    private static final String PET_REQUIRED_KEYWORD = "#/properties/pet/required";

    private static final String PET_LOCATION = "#/pet";

    @Test
    @DisplayName("an omitted required member is named from the schema's required array, under the parent the"
            + " declared names allow, and nowhere else")
    void omittedRequiredMemberIsNamedFromTheSchema() {
        // Given the schemas of the contract, each validated for real with an instance that omits the members
        String petNames = "['name','a/b','t~x','a%20b','we\\\"ird','']";
        JsonObject direct = Schemas.json("{'properties':{'pet':{'type':'object','required':" + petNames + "}}}");
        JsonObject throughRef = Schemas.json("{'properties':{'pet':{'$ref':'#/$defs/Pet'}},"
                + "'$defs':{'Pet':{'type':'object','required':" + petNames + "}}}");
        JsonObject slashParent = Schemas.json("{'properties':{'a/b':{'type':'object','required':['name']}}}");
        JsonObject inArray = Schemas.json(
                "{'properties':{'items':{'type':'array','items':" + "{'type':'object','required':['name']}}}}");
        JsonObject underAdditional = Schemas.json("{'additionalProperties':{'type':'object','required':['name']}}");
        JsonObject underMapKey = Schemas.json("{'properties':{'byKey':{'type':'object','additionalProperties':"
                + "{'type':'object','required':['name']}}}}");
        JsonObject underPattern = Schemas.json("{'patternProperties':{'^x':{'type':'object','required':['name']}}}");
        JsonObject allOf = Schemas.json("{'allOf':[{'required':['a']},{'required':['b']}]}");
        JsonObject anyOf = Schemas.json("{'anyOf':[{'required':['a']},{'required':['b']}]}");
        String pets = "'$defs':{'Pet':{'type':'object','required':['name']},"
                + "'Cat':{'type':'object','required':['meow']}}";
        JsonObject nullFirst =
                Schemas.json("{'properties':{'pet':{'anyOf':[{'type':'null'},{'$ref':'#/$defs/Pet'}]}}," + pets + "}");
        JsonObject nullLast =
                Schemas.json("{'properties':{'pet':{'anyOf':[{'$ref':'#/$defs/Pet'},{'type':'null'}]}}," + pets + "}");
        JsonObject nullInline = Schemas.json(
                "{'properties':{'pet':{'anyOf':[{'type':'null'}," + "{'type':'object','required':['name']}]}}}");
        JsonObject twoObjects = Schemas.json(
                "{'properties':{'pet':{'anyOf':[{'$ref':'#/$defs/Pet'},{'$ref':'#/$defs/Cat'}]}}," + pets + "}");
        JsonObject oneOfNull =
                Schemas.json("{'properties':{'pet':{'oneOf':[{'type':'null'},{'$ref':'#/$defs/Pet'}]}}," + pets + "}");

        // When each result is collected in the RFC 6901 form
        // Then the named members carry the escaped name, and the parent is kept where the schema cannot confirm it
        assertAll(
                () -> assertThat(requiredPaths(direct, "{'pet':{}}"))
                        .as("a plain required array")
                        .containsExactlyInAnyOrder(
                                "/pet/name", "/pet/a~1b", "/pet/t~0x", "/pet/a%20b", "/pet/we\"ird", "/pet/"),
                () -> assertThat(requiredPaths(throughRef, "{'pet':{}}"))
                        .as("the same names through a $ref")
                        .containsExactlyInAnyOrder(
                                "/pet/name", "/pet/a~1b", "/pet/t~0x", "/pet/a%20b", "/pet/we\"ird", "/pet/"),
                () -> assertThat(requiredPaths(slashParent, "{'a/b':{}}"))
                        .as("a parent named a/b is escaped once")
                        .containsExactly("/a~1b/name"),
                () -> assertThat(requiredPaths(inArray, "{'items':[{}]}"))
                        .as("an array element")
                        .containsExactly("/items/0/name"),
                () -> assertThat(requiredPaths(underAdditional, "{'zz':{}}"))
                        .as("under additionalProperties")
                        .containsExactly(""),
                () -> assertThat(requiredPaths(underMapKey, "{'byKey':{'ZZKEY':{}}}"))
                        .as("under a map key")
                        .containsExactly("/byKey"),
                () -> assertThat(requiredPaths(underPattern, "{'xZZKEY':{}}"))
                        .as("under patternProperties")
                        .containsExactly(""),
                () -> assertThat(requiredPaths(allOf, "{}"))
                        .as("every allOf branch is named")
                        .containsExactlyInAnyOrder("/a", "/b"),
                () -> assertThat(requiredPaths(anyOf, "{}"))
                        .as("alternatives keep the parent")
                        .containsExactly("", ""),
                () -> assertThat(requiredPaths(nullFirst, "{'pet':{}}"))
                        .as("a nullable optional pet, null first")
                        .containsExactly("/pet/name"),
                () -> assertThat(requiredPaths(nullLast, "{'pet':{}}"))
                        .as("a nullable optional pet, null last")
                        .containsExactly("/pet/name"),
                () -> assertThat(requiredPaths(nullInline, "{'pet':{}}"))
                        .as("a nullable optional pet with an inline object branch")
                        .containsExactly("/pet/name"),
                () -> assertThat(details(nullFirst, "{'pet':{}}"))
                        .as("the null branch's own type unit is not touched")
                        .extracting(ViolationDetail::path, ViolationDetail::type)
                        .containsExactlyInAnyOrder(tuple("/pet", "type"), tuple("/pet/name", "required")),
                () -> assertThat(requiredPaths(twoObjects, "{'pet':{}}"))
                        .as("a real two-object anyOf")
                        .containsExactly("/pet", "/pet"),
                () -> assertThat(requiredPaths(oneOfNull, "{'pet':{}}"))
                        .as("a oneOf of null and an object")
                        .containsExactly("/pet"),
                () -> assertThat(requiredPathsOfUnits(
                                PET_REQUIRES_NAME,
                                requiredUnit(PET_REQUIRED_KEYWORD, PET_LOCATION, "ZZKEY"),
                                ResultShapes.unitWithMessage(
                                        PET_REQUIRED_KEYWORD, PET_LOCATION, "Missing required member \"name\""),
                                ResultShapes.unitWithMessage(
                                        PET_REQUIRED_KEYWORD, PET_LOCATION, MISSING_PREFIX + "name")))
                        .as("a name the schema does not require, a foreign message and an unterminated message")
                        .containsExactly("/pet", "/pet", "/pet"));
    }

    @Nested
    @DisplayName("naming an omitted required member")
    class RequiredMemberNaming {

        @Test
        @DisplayName("the named detail keeps the engine's message, the keyword and its argument, and fail-fast"
                + " stops at the first named member")
        void theNamedDetailKeepsItsMessageKeywordAndArguments() {
            JsonObject schema = Schemas.json("{'properties':{'pet':{'required':['name','age']}}}");
            OutputUnit result = Validators.validate(schema, Schemas.json("{'pet':{}}"));

            List<ViolationDetail> all = ViolationDetails.collect(result, schema, false, PathForm.RFC6901);
            List<ViolationDetail> first = ViolationDetails.collect(result, schema, true, PathForm.RFC6901);

            assertThat(all)
                    .containsExactlyInAnyOrder(
                            new ViolationDetail(
                                    "/pet/name", MISSING_PREFIX + "name\"", "required", Map.of("required", true)),
                            new ViolationDetail(
                                    "/pet/age", MISSING_PREFIX + "age\"", "required", Map.of("required", true)));
            assertThat(first).hasSize(1);
            assertThat(first.get(0).path()).isIn("/pet/name", "/pet/age");
        }

        @Test
        @DisplayName("a root member is named with one slash, and an empty name is the lone slash")
        void aRootMemberIsNamedAtTheRoot() {
            JsonObject named = Schemas.json("{'required':['name']}");
            JsonObject empty = Schemas.json("{'required':['']}");

            assertThat(requiredPaths(named, "{}")).containsExactly("/name");
            assertThat(requiredPaths(empty, "{}")).containsExactly("/");
        }

        @Test
        @DisplayName("then and else branches are named")
        void thenAndElseAreNamed() {
            JsonObject schema =
                    Schemas.json("{'if':{'required':['kind']},'then':{'required':['a']},'else':{'required':['b']}}");

            assertThat(requiredPaths(schema, "{'kind':1}")).containsExactly("/a");
            assertThat(requiredPaths(schema, "{}")).containsExactly("/b");
        }

        @Test
        @DisplayName("a name is the text between the engine's prefix and the final quote, not up to the first"
                + " closing quote")
        void theNameRunsToTheFinalQuote() {
            JsonObject schema = Schemas.json("{'properties':{'pet':{'required':['a\\\"b','a','\\\"','a\\\"']}}}");

            // Given a real validation whose names contain quotes, and the name that is the prefix of another
            List<String> real = requiredPaths(schema, "{'pet':{}}");
            // And a hand-built unit whose message holds nothing after the opening quote
            List<String> prefixOnly = requiredPathsOfUnits(
                    Schemas.json("{'properties':{'pet':{'required':['']}}}"),
                    ResultShapes.unitWithMessage(PET_REQUIRED_KEYWORD, PET_LOCATION, MISSING_PREFIX));

            // Then each name is cut at the final quote, and a message that is only the prefix names nothing
            assertThat(real).containsExactlyInAnyOrder("/pet/a\"b", "/pet/a", "/pet/\"", "/pet/a\"");
            assertThat(prefixOnly).containsExactly("/pet");
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("messagesThatDoNotConfirmAName")
        @DisplayName("a message that does not follow the engine's shape keeps the parent, where the exact shape"
                + " names the member")
        void aMessageOutsideTheEnginesShapeKeepsTheParent(String label, String message) {
            JsonObject schema = Schemas.json("{'properties':{'pet':{'required':['name']}}}");

            List<String> control =
                    requiredPathsOfUnits(schema, requiredUnit(PET_REQUIRED_KEYWORD, PET_LOCATION, "name"));
            List<String> variant = requiredPathsOfUnits(
                    schema, ResultShapes.unitWithMessage(PET_REQUIRED_KEYWORD, PET_LOCATION, message));

            assertAll(
                    () -> assertThat(control)
                            .as("the exact engine message names the member")
                            .containsExactly("/pet/name"),
                    () -> assertThat(variant).as(label).containsExactly("/pet"));
        }

        static Stream<Arguments> messagesThatDoNotConfirmAName() {
            return Stream.of(
                    Arguments.of("another prefix", "Missing required property \"name\""),
                    Arguments.of("a different case", "instance does not have required property \"name\""),
                    Arguments.of("a leading space", " " + MISSING_PREFIX + "name\""),
                    Arguments.of("a trailing space", MISSING_PREFIX + "name\" "),
                    Arguments.of("a trailing period", MISSING_PREFIX + "name\"."),
                    Arguments.of("no closing quote", MISSING_PREFIX + "name"),
                    Arguments.of("no quotes at all", "Instance does not have required property name"),
                    Arguments.of("the prefix only", MISSING_PREFIX),
                    Arguments.of("an empty message", ""),
                    Arguments.of("a different case of the name", MISSING_PREFIX + "Name\""),
                    Arguments.of("a name that extends a required one", MISSING_PREFIX + "name2\""),
                    Arguments.of("a name that is a prefix of the required one", MISSING_PREFIX + "nam\""),
                    Arguments.of("a name padded with a space", MISSING_PREFIX + " name\""),
                    Arguments.of("a name that is not required", MISSING_PREFIX + "ZZKEY\""));
        }

        @Test
        @DisplayName("a name the schema requires elsewhere, an unreachable keyword location and an absent schema keep"
                + " the parent")
        void aNameThatTheSchemaCannotConfirmHereKeepsTheParent() {
            JsonObject schema = Schemas.json("{'properties':{'pet':{'required':['name']},"
                    + "'owner':{'required':['other']},'plain':{'type':'object'}}}");

            // Given a unit for a name only the sibling requires, one for a location with no required array, one
            // for a location the schema does not have, and the exact unit with no schema at all
            List<String> siblingName =
                    requiredPathsOfUnits(schema, requiredUnit(PET_REQUIRED_KEYWORD, PET_LOCATION, "other"));
            List<String> noArray =
                    requiredPathsOfUnits(schema, requiredUnit("#/properties/plain/required", "#/plain", "name"));
            List<String> noLocation =
                    requiredPathsOfUnits(schema, requiredUnit("#/properties/nope/required", "#/nope", "name"));
            List<String> noSchema = ViolationDetails.collect(
                            ResultShapes.multiError(requiredUnit(PET_REQUIRED_KEYWORD, PET_LOCATION, "name")),
                            null,
                            false,
                            PathForm.RFC6901)
                    .stream()
                    .map(ViolationDetail::path)
                    .toList();

            // Then each keeps the engine's parent location, cut back to what the schema declares, and the exact
            // unit against the same schema is named
            assertAll(
                    () -> assertThat(requiredPathsOfUnits(
                                    schema, requiredUnit(PET_REQUIRED_KEYWORD, PET_LOCATION, "name")))
                            .as("control: the exact unit against the same schema")
                            .containsExactly("/pet/name"),
                    () -> assertThat(siblingName)
                            .as("a name only the sibling requires")
                            .containsExactly("/pet"),
                    () -> assertThat(noArray)
                            .as("a location with no required array")
                            .containsExactly("/plain"),
                    () -> assertThat(noLocation)
                            .as("a location the schema lacks")
                            .containsExactly(""),
                    () -> assertThat(noSchema)
                            .as("no schema, so nothing is declared")
                            .containsExactly(""));
        }

        @Test
        @DisplayName("a parent under a client's key keeps its cut-back form, also inside an array, while a"
                + " declared sibling is named")
        void aParentUnderAClientKeyKeepsItsCutBackForm() {
            JsonObject inArrayMap = Schemas.json("{'properties':{'items':{'type':'array','items':"
                    + "{'type':'object','additionalProperties':{'type':'object','required':['name']}}}}}");
            JsonObject inArrayPattern = Schemas.json("{'properties':{'items':{'type':'array','items':"
                    + "{'type':'object','patternProperties':{'^x':{'type':'object','required':['name']}}}}}}");
            JsonObject inArrayDeclared = Schemas.json("{'properties':{'items':{'type':'array','items':"
                    + "{'type':'object','properties':{'owner':{'type':'object','required':['name']}}}}}}");
            JsonObject deepMap = Schemas.json("{'properties':{'a':{'properties':{'b':{'type':'object',"
                    + "'additionalProperties':{'properties':{'c':{'type':'object','required':['name']}}}}}}}}");

            List<ViolationDetail> map = details(inArrayMap, "{'items':[{'ZZKEY':{}}]}");
            List<ViolationDetail> pattern = details(inArrayPattern, "{'items':[{'xZZKEY':{}}]}");
            List<ViolationDetail> declared = details(inArrayDeclared, "{'items':[{'owner':{}}]}");
            List<ViolationDetail> deep = details(deepMap, "{'a':{'b':{'ZZKEY':{'c':{}}}}}");

            assertAll(
                    () -> assertThat(requiredOnly(declared))
                            .as("control: a declared sibling inside the same array")
                            .containsExactly("/items/0/owner/name"),
                    () -> assertThat(requiredOnly(map))
                            .as("additionalProperties in an array element")
                            .containsExactly("/items/0"),
                    () -> assertThat(requiredOnly(pattern))
                            .as("patternProperties in an array element")
                            .containsExactly("/items/0"),
                    () -> assertThat(requiredOnly(deep))
                            .as("a map key three levels down")
                            .containsExactly("/a/b"),
                    () -> assertNoClientKey(map),
                    () -> assertNoClientKey(pattern),
                    () -> assertNoClientKey(deep));
        }

        @Test
        @DisplayName("a oneOf or a not step keeps the parent, also below a branch that is named")
        void aOneOfOrNotStepKeepsTheParent() {
            JsonObject schema = Schemas.json("{'properties':{'pet':{"
                    + "'allOf':[{'required':['name']}],"
                    + "'oneOf':[{'required':['name']},{'required':['name']}],"
                    + "'not':{'required':['name']},"
                    + "'anyOf':[{'type':'null'},{'oneOf':[{'required':['name']}]}]}}}");

            List<String> allOf = requiredPathsOfUnits(
                    schema, requiredUnit("#/properties/pet/allOf/0/required", PET_LOCATION, "name"));
            List<String> oneOf = requiredPathsOfUnits(
                    schema, requiredUnit("#/properties/pet/oneOf/1/required", PET_LOCATION, "name"));
            List<String> not =
                    requiredPathsOfUnits(schema, requiredUnit("#/properties/pet/not/required", PET_LOCATION, "name"));
            List<String> oneOfUnderNullable = requiredPathsOfUnits(
                    schema, requiredUnit("#/properties/pet/anyOf/1/oneOf/0/required", PET_LOCATION, "name"));

            assertAll(
                    () -> assertThat(allOf).as("control: an allOf branch").containsExactly("/pet/name"),
                    () -> assertThat(oneOf).as("a oneOf branch").containsExactly("/pet"),
                    () -> assertThat(not).as("a not step").containsExactly("/pet"),
                    () -> assertThat(oneOfUnderNullable)
                            .as("a oneOf below a nullable anyOf")
                            .containsExactly("/pet"));
        }

        @Test
        @DisplayName("a oneOf or a not above an allOf keeps the parent")
        void aOneOfOrNotAboveAnAllOfKeepsTheParent() {
            JsonObject schema = Schemas.json("{'properties':{'pet':{"
                    + "'oneOf':[{'allOf':[{'required':['name']}]}],"
                    + "'not':{'allOf':[{'required':['name']}]},"
                    + "'allOf':[{'oneOf':[{'required':['name']}]},{'allOf':[{'required':['name']}]}]}}}");

            assertAll(
                    () -> assertThat(requiredPathsOfUnits(
                                    schema,
                                    requiredUnit("#/properties/pet/allOf/1/allOf/0/required", PET_LOCATION, "name")))
                            .as("control: an allOf inside an allOf")
                            .containsExactly("/pet/name"),
                    () -> assertThat(requiredPathsOfUnits(
                                    schema,
                                    requiredUnit("#/properties/pet/oneOf/0/allOf/0/required", PET_LOCATION, "name")))
                            .as("an allOf inside a oneOf")
                            .containsExactly("/pet"),
                    () -> assertThat(requiredPathsOfUnits(
                                    schema,
                                    requiredUnit("#/properties/pet/not/allOf/0/required", PET_LOCATION, "name")))
                            .as("an allOf inside a not")
                            .containsExactly("/pet"),
                    () -> assertThat(requiredPathsOfUnits(
                                    schema,
                                    requiredUnit("#/properties/pet/allOf/0/oneOf/0/required", PET_LOCATION, "name")))
                            .as("a oneOf inside an allOf")
                            .containsExactly("/pet"));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("anyOfShapes")
        @DisplayName("an anyOf is see-through only when it is exactly a null branch and one other branch")
        void onlyAnExactNullablePairIsSeeThrough(
                String label, String anyOfEntries, String keywordLocation, String expectedPath) {
            JsonObject schema = Schemas.json("{'properties':{'pet':{'anyOf':" + anyOfEntries + "}},"
                    + "'$defs':{'Pet':{'type':'object','required':['name']}}}");

            List<String> hand = requiredPathsOfUnits(schema, requiredUnit(keywordLocation, PET_LOCATION, "name"));

            assertThat(hand).as(label).containsExactly(expectedPath);
        }

        static Stream<Arguments> anyOfShapes() {
            String namedBranch = "{'type':'object','required':['name']}";
            return Stream.of(
                    Arguments.of(
                            "control: null first, inline branch second",
                            "[{'type':'null'}," + namedBranch + "]",
                            "#/properties/pet/anyOf/1/required",
                            "/pet/name"),
                    Arguments.of(
                            "control: null last, inline branch first",
                            "[" + namedBranch + ",{'type':'null'}]",
                            "#/properties/pet/anyOf/0/required",
                            "/pet/name"),
                    Arguments.of(
                            "control: null first, referenced branch second",
                            "[{'type':'null'},{'$ref':'#/$defs/Pet'}]",
                            "#/properties/pet/anyOf/1/$ref/required",
                            "/pet/name"),
                    Arguments.of(
                            "neither entry is a null branch",
                            "[" + namedBranch + ",{'type':'string'}]",
                            "#/properties/pet/anyOf/0/required",
                            "/pet"),
                    Arguments.of(
                            "the other entry is a second object",
                            "[{'type':'object','required':['x']}," + namedBranch + "]",
                            "#/properties/pet/anyOf/1/required",
                            "/pet"),
                    Arguments.of(
                            "three entries, the unit on the first",
                            "[" + namedBranch + ",{'type':'null'},{'type':'string'}]",
                            "#/properties/pet/anyOf/0/required",
                            "/pet"),
                    Arguments.of(
                            "three entries with a null among them, the unit on the last",
                            "[{'type':'null'},{'type':'string'}," + namedBranch + "]",
                            "#/properties/pet/anyOf/2/required",
                            "/pet"),
                    Arguments.of(
                            "a single entry", "[" + namedBranch + "]", "#/properties/pet/anyOf/0/required", "/pet"),
                    Arguments.of(
                            "the null branch is a type list",
                            "[{'type':['null']}," + namedBranch + "]",
                            "#/properties/pet/anyOf/1/required",
                            "/pet"),
                    Arguments.of(
                            "the null branch carries a second keyword",
                            "[{'type':'null','description':'none'}," + namedBranch + "]",
                            "#/properties/pet/anyOf/1/required",
                            "/pet"),
                    Arguments.of(
                            "the null branch names another type",
                            "[{'type':'string'}," + namedBranch + "]",
                            "#/properties/pet/anyOf/1/required",
                            "/pet"),
                    Arguments.of(
                            "the other entry is an empty schema",
                            "[{}," + namedBranch + "]",
                            "#/properties/pet/anyOf/1/required",
                            "/pet"),
                    Arguments.of(
                            "the other entry is the boolean true",
                            "[true," + namedBranch + "]",
                            "#/properties/pet/anyOf/1/required",
                            "/pet"));
        }

        @Test
        @DisplayName("nested nullable pairs are see-through, and a nullable pair above a plain anyOf is not")
        void nestedNullablePairs() {
            JsonObject nested = Schemas.json("{'properties':{'pet':{'anyOf':[{'type':'null'},"
                    + "{'anyOf':[{'type':'null'},{'required':['a']}]}]}}}");
            JsonObject mixed = Schemas.json("{'properties':{'pet':{'anyOf':[{'type':'null'},"
                    + "{'anyOf':[{'required':['a']},{'required':['b']}]}]}}}");
            JsonObject belowAllOf = Schemas.json("{'allOf':[{'anyOf':[{'type':'null'},{'required':['a']}]}]}");

            assertAll(
                    () -> assertThat(requiredPaths(nested, "{'pet':{}}"))
                            .as("a nullable pair inside a nullable pair")
                            .containsExactly("/pet/a"),
                    () -> assertThat(requiredPaths(mixed, "{'pet':{}}"))
                            .as("a plain anyOf inside a nullable pair")
                            .containsExactly("/pet", "/pet"),
                    () -> assertThat(requiredPaths(belowAllOf, "{}"))
                            .as("a nullable pair inside an allOf")
                            .containsExactly("/a"));
        }

        @Test
        @DisplayName("a $ref step before the anyOf keeps the parent, while a $ref after it is followed")
        void aRefStepBeforeTheAnyOfKeepsTheParent() {
            String maybeInline = "{'anyOf':[{'type':'null'},{'type':'object','required':['name']}]}";
            String maybeReferenced = "{'anyOf':[{'type':'null'},{'$ref':'#/$defs/Pet'}]}";
            String pet = "'Pet':{'type':'object','required':['name']}";
            JsonObject before = Schemas.json(
                    "{'properties':{'pet':{'$ref':'#/$defs/Maybe'}}," + "'$defs':{'Maybe':" + maybeInline + "}}");
            JsonObject beforeAndAfter = Schemas.json("{'properties':{'pet':{'$ref':'#/$defs/Maybe'}},"
                    + "'$defs':{'Maybe':" + maybeReferenced + "," + pet + "}}");
            JsonObject after = Schemas.json("{'properties':{'pet':{'anyOf':[{'type':'null'},{'$ref':'#/$defs/Pet'}]}},"
                    + "'$defs':{" + pet + "}}");

            List<ViolationDetail> beforeDetails = details(before, "{'pet':{}}");
            List<ViolationDetail> beforeAndAfterDetails = details(beforeAndAfter, "{'pet':{}}");

            assertAll(
                    () -> assertThat(requiredOnly(details(after, "{'pet':{}}")))
                            .as("control: the same pair reached without a $ref step")
                            .containsExactly("/pet/name"),
                    () -> assertThat(requiredOnly(beforeDetails))
                            .as("a $ref before an inline branch")
                            .containsExactly("/pet"),
                    () -> assertThat(requiredOnly(beforeAndAfterDetails))
                            .as("a $ref before and after")
                            .containsExactly("/pet"),
                    () -> assertThat(requiredPathsOfUnits(
                                    before,
                                    requiredUnit("#/properties/pet/$ref/anyOf/1/required", PET_LOCATION, "name")))
                            .as("the unit as the contract spells it")
                            .containsExactly("/pet"));
        }

        @Test
        @DisplayName("a pointer-significant name is escaped once: tilde first, then slash, and a parent is not"
                + " escaped again")
        void pointerSignificantNamesAreEscapedOnce() {
            JsonObject schema =
                    Schemas.json("{'properties':{'pet':{'required':['~','/','~1','~0','a~/b','~~','//']}}}");
            JsonObject parents = Schemas.json("{'properties':{'a/b':{'required':['/']},"
                    + "'t~x':{'required':['~1']},'c~/d':{'required':['n~']}}}");

            List<String> names = requiredPaths(schema, "{'pet':{}}");
            List<String> underParents = requiredPaths(parents, "{'a/b':{},'t~x':{},'c~/d':{}}");

            assertThat(names)
                    .containsExactlyInAnyOrder(
                            "/pet/~0", "/pet/~1", "/pet/~01", "/pet/~00", "/pet/a~0~1b", "/pet/~0~0", "/pet/~1~1");
            assertThat(underParents).containsExactlyInAnyOrder("/a~1b/~1", "/t~0x/~01", "/c~0~1d/n~0");
        }

        @Test
        @DisplayName("a name is never percent-decoded, and a percent sign stays literal")
        void aNameIsNeverPercentDecoded() {
            JsonObject schema = Schemas.json("{'properties':{'pet':{'required':['a%2Fb','%7E','100%','%zz','a+b']}}}");

            assertThat(requiredPaths(schema, "{'pet':{}}"))
                    .containsExactlyInAnyOrder("/pet/a%2Fb", "/pet/%7E", "/pet/100%", "/pet/%zz", "/pet/a+b");
        }

        @Test
        @DisplayName("a name listed twice is named for each unit the engine emits")
        void aRepeatedNameIsNamedForEachUnit() {
            JsonObject schema = Schemas.json("{'properties':{'pet':{'required':['name','name']}}}");

            List<String> paths = requiredPaths(schema, "{'pet':{}}");

            assertThat(paths).isNotEmpty().containsOnly("/pet/name");
        }

        @Test
        @DisplayName("a very long declared name is named in full")
        void aVeryLongNameIsNamedInFull() {
            String longName = "n".repeat(10_000);
            JsonObject schema = new JsonObject()
                    .put(
                            "properties",
                            new JsonObject()
                                    .put("pet", new JsonObject().put("required", new JsonArray().add(longName))));

            List<String> paths = requiredPaths(schema, "{'pet':{}}");

            assertThat(paths.equals(List.of("/pet/" + longName)))
                    .as("one path of 10,006 characters ending in the full name, but was "
                            + paths.stream()
                                    .map(path -> path.length() + " characters")
                                    .toList())
                    .isTrue();
        }

        @Test
        @DisplayName("a name that is the engine's own wording is taken from the schema, not from the message")
        void aNameThatEchoesTheMessageShapeIsNamedFromTheSchema() {
            String odd = "Instance does not have required property \\\"x\\\"";
            JsonObject schema = Schemas.json("{'properties':{'pet':{'required':['" + odd + "']}}}");

            assertThat(requiredPaths(schema, "{'pet':{}}"))
                    .containsExactly("/pet/Instance does not have required property \"x\"");
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"name", "a b", "a/b", "t~x", "a%20b", "we\\\"ird", ""})
        @DisplayName("in the fragment form the name is spelled as the engine spells the same member when present")
        void theFragmentFormSpellsTheNameAsTheEngineDoes(String name) {
            String quoted = "'" + name + "'";
            JsonObject schema = Schemas.json("{'properties':{'pet':{'type':'object','properties':{" + quoted
                    + ":{'type':'integer'}},'required':[" + quoted + "]}}}");
            String present = "{'pet':{" + quoted + ":'x'}}";

            List<ViolationDetail> wrongType = ViolationDetails.collect(
                    Validators.validate(schema, Schemas.json(present)), schema, false, PathForm.URI_FRAGMENT);
            List<ViolationDetail> omitted = ViolationDetails.collect(
                    Validators.validate(schema, Schemas.json("{'pet':{}}")), schema, false, PathForm.URI_FRAGMENT);

            assertThat(wrongType).extracting(ViolationDetail::type).containsExactly("type");
            assertThat(omitted).extracting(ViolationDetail::type).containsExactly("required");
            assertThat(omitted.get(0).path()).isEqualTo(wrongType.get(0).path());
        }

        @Test
        @DisplayName("no path ever carries the client's key, whatever the shape of the schema")
        void noPathCarriesTheClientKey() {
            JsonObject schema = Schemas.json("{'properties':{'byKey':{'type':'object','additionalProperties':"
                    + "{'type':'object','required':['name']}},'list':{'type':'array','items':{'type':'object',"
                    + "'patternProperties':{'^x':{'type':'object','required':['name']}}}},"
                    + "'owner':{'type':'object','required':['name']}},"
                    + "'additionalProperties':{'type':'object','required':['name']}}");

            List<ViolationDetail> details = details(
                    schema, "{'byKey':{'ZZKEY':{}},'list':[{'xZZKEY':{}}],'ZZKEY':{},'other':{'ZZKEY':{}},'owner':{}}");

            // The engine applies the root additionalProperties schema to the declared keys as well, so it
            // reports a required failure under byKey and a second one under owner.
            List<String> cutBack = List.of("", "/byKey", "/list/0");
            assertAll(
                    () -> assertThat(requiredOnly(details))
                            .as("control: declared parents are named, every client key is cut back")
                            .containsExactlyInAnyOrder(
                                    "/owner/name", "/owner/name", "/byKey", "/byKey/name", "/list/0", "", ""),
                    () -> assertThat(requiredOnly(details))
                            .as("every appended name is one the schema's required arrays list")
                            .allSatisfy(path -> assertThat(cutBack.contains(path) || path.endsWith("/name"))
                                    .isTrue()),
                    () -> assertNoClientKey(details));
        }
    }

    // --- required-member helpers ---

    /** Validates {@code instance} for real and returns every detail in the RFC 6901 form. */
    private static List<ViolationDetail> details(JsonObject schema, String instance) {
        OutputUnit result = Validators.validate(schema, Schemas.json(instance));
        return ViolationDetails.collect(result, schema, false, PathForm.RFC6901);
    }

    /** The RFC 6901 paths of the {@code required} details a real validation of {@code instance} reports. */
    private static List<String> requiredPaths(JsonObject schema, String instance) {
        return requiredOnly(details(schema, instance));
    }

    /** The RFC 6901 paths of the {@code required} details collected from hand-built units. */
    private static List<String> requiredPathsOfUnits(JsonObject schema, OutputUnit... units) {
        return requiredOnly(ViolationDetails.collect(ResultShapes.multiError(units), schema, false, PathForm.RFC6901));
    }

    private static List<String> requiredOnly(List<ViolationDetail> details) {
        return details.stream()
                .filter(detail -> "required".equals(detail.type()))
                .map(ViolationDetail::path)
                .toList();
    }

    /** Builds a unit in the engine's shape for an omitted required member. */
    private static OutputUnit requiredUnit(String keywordLocation, String instanceLocation, String name) {
        return ResultShapes.unitWithMessage(keywordLocation, instanceLocation, MISSING_PREFIX + name + "\"");
    }

    /** Asserts that no path names the key a client chose; the engine's message may still carry it. */
    private static void assertNoClientKey(List<ViolationDetail> details) {
        assertThat(details).extracting(ViolationDetail::path).noneMatch(path -> path.contains("ZZKEY"));
    }

    /** One result the fallback test collects, with the path its single value-free detail must carry. */
    private record FallbackCase(String label, OutputUnit result, JsonObject schema, String expectedPath) {}

    /** Builders for hand-built results in the shapes the engine's Basic output has. */
    static final class ResultShapes {

        private ResultShapes() {}

        /**
         * Builds one failing error whose raw message echoes {@link #SECRET}.
         *
         * @param keywordLocation the keyword location, possibly {@code null} or empty
         * @param instanceLocation the instance location
         * @return the error unit
         */
        static OutputUnit leaf(String keywordLocation, String instanceLocation) {
            return new OutputUnit()
                    .setValid(false)
                    .setKeywordLocation(keywordLocation)
                    .setInstanceLocation(instanceLocation)
                    .setError("the value " + SECRET + " does not satisfy an unresolvable constraint");
        }

        /**
         * Builds one failing error with the engine's own message, as for an omitted required member.
         *
         * @param keywordLocation the keyword location
         * @param instanceLocation the instance location
         * @param message the engine's message
         * @return the error unit
         */
        static OutputUnit unitWithMessage(String keywordLocation, String instanceLocation, String message) {
            return new OutputUnit()
                    .setValid(false)
                    .setKeywordLocation(keywordLocation)
                    .setInstanceLocation(instanceLocation)
                    .setError(message);
        }

        /**
         * Builds a result that is itself the one error, with no nested {@code errors} list.
         *
         * @param keywordLocation the keyword location, possibly {@code null} or empty
         * @param instanceLocation the instance location
         * @return the result
         */
        static OutputUnit singleError(String keywordLocation, String instanceLocation) {
            return leaf(keywordLocation, instanceLocation);
        }

        /**
         * Builds a failing result carrying the given errors in a nested {@code errors} list.
         *
         * @param errors the errors
         * @return the result
         */
        static OutputUnit multiError(OutputUnit... errors) {
            return new OutputUnit().setValid(false).setErrors(List.of(errors));
        }
    }
}
